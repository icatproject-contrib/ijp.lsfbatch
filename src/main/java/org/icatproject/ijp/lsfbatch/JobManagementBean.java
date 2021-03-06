package org.icatproject.ijp.lsfbatch;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.ejb.Schedule;
import javax.ejb.Stateless;
import javax.json.Json;
import javax.json.stream.JsonGenerator;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.xml.namespace.QName;

import org.icatproject.ICATService;
import org.icatproject.IcatExceptionType;
import org.icatproject.IcatException_Exception;
import org.icatproject.ijp.batch.BatchJson;
import org.icatproject.ijp.batch.JobStatus;
import org.icatproject.ijp.batch.OutputType;
import org.icatproject.ijp.batch.exceptions.ForbiddenException;
import org.icatproject.ijp.batch.exceptions.InternalException;
import org.icatproject.ijp.batch.exceptions.ParameterException;
import org.icatproject.ijp.batch.exceptions.SessionException;
import org.icatproject.utils.CheckedProperties;
import org.icatproject.utils.ShellCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session Bean implementation to manage job status
 */
@Stateless
public class JobManagementBean {

	private String defaultFamily;
	private Map<String, List<String>> families = new HashMap<>();
	private LsfUserPool lsfUserPool;

	private Path jobOutputDir;

	private String lsfDefaultQueue;
	private String lsfUserPoolBaseDir;
	private String lsfUserOutputDir;
	
	private final static String chars = "abcdefghijklmnpqrstuvwxyz";

	private QName qName = new QName("http://icatproject.org", "ICATService");

	@PostConstruct
	void init() {

		try {
			CheckedProperties props = new CheckedProperties();
			props.loadFromFile(Constants.PROPERTIES_FILEPATH);

			String familiesList = props.getString("families.list");
			for (String mnemonic : familiesList.split("\\s+")) {
				if (defaultFamily == null) {
					defaultFamily = mnemonic;
				}
				String key = "families." + mnemonic;
				String[] members = props.getProperty(key).split("\\s+");
				families.put(mnemonic, new ArrayList<>(Arrays.asList(members)));
				logger.debug("Family " + mnemonic + " contains " + families.get(mnemonic));
			}
			if (defaultFamily == null) {
				String msg = "No families defined";
				logger.error(msg);
				throw new IllegalStateException(msg);
			}

			lsfUserPool = LsfUserPool.getInstance();
			for( String family : families.keySet() ){
				for( String lsfUserId : families.get(family) ){
					// See if any unfinished Jobs are assigned to this id
					boolean isAssigned = false;
					for (LsfJob job : entityManager.createNamedQuery(LsfJob.FIND_BY_BATCHUSERNAME, LsfJob.class)
							.setParameter("batchusername", lsfUserId).getResultList()) {
						JobStatus status = job.getStatus();
						if( status != null && ! isFinished(status) ){
							isAssigned = true;
						}
					}
					logger.debug("adding Lsf User: family '" + family + "', id '" + lsfUserId + "', assigned=" + isAssigned);
					lsfUserPool.addLsfUser(family, lsfUserId, isAssigned);
				}
			}

			jobOutputDir = props.getPath("jobOutputDir");
			if (!jobOutputDir.toFile().exists()) {
				String msg = "jobOutputDir " + jobOutputDir + "does not exist";
				logger.error(msg);
				throw new IllegalStateException(msg);
			}
			jobOutputDir = jobOutputDir.toAbsolutePath();

			lsfDefaultQueue = props.getString("lsf.defaultQueue");
			
			// TODO lsfUserPoolBaseDir could be checked as a Path, but was refactored from a String constant and left as a String for now
			
			lsfUserPoolBaseDir = props.getString("lsf.userPoolBaseDir");
			lsfUserOutputDir = props.getString("lsf.userOutputDir");
			
			logger.info("Set up lsfbatch with default family " + defaultFamily);
		} catch (Exception e) {
			String msg = e.getClass() + " reports " + e.getMessage();
			logger.error(msg);
			throw new IllegalStateException(msg);
		}

	}

	private final static Logger logger = LoggerFactory.getLogger(JobManagementBean.class);
	private final static Random random = new Random();

	@PersistenceContext(unitName = "lsfbatch")
	private EntityManager entityManager;

	/**
	 * updateJobsFromBjobs() is a scheduled method (every minute) to update the status of all known jobs for each LSF pool user.
	 * For a particular pool user, if bjobs returns no jobs, or if all jobs are finished, the pool user is
	 * released back to the pool (this may be a no-op as they may already be released).  At this point, any jobs with
	 * an unfinished status will be assumed Completed (as they are no longer appearing in the bjobs output).
	 * Additionally, if any job's status changes to Completed, the job's output is moved from the pool user account to the
	 * glassfish holding area.  Jobs with status Cancelled require special care: bjobs will return a status of Completed
	 * (mapped from EXIT), so we need to move their output if it hasn't been moved already.
	 * As this is a scheduled method, any exceptions that may be raised from execution of bjobs, file moves or cleanups
	 * will be caught and (merely) logged.
	 */
	@Schedule(minute = "*/1", hour = "*")
	public void updateJobsFromBjobs() {
		try {

			// Need to run bjobs for *each* (active) user in the pool; glassfish user should be able to do this, no need for ssh
			
			List<String> activePoolUsers = getActivePoolUsers();
			logger.debug("Active pool users: " + activePoolUsers );
			
			for( String poolUserId : getActivePoolUsers() ){
			
				ShellCommand sc = new ShellCommand("bjobs", "-aw", "-u", poolUserId );
				if (sc.isError()) {
					// Astonishingly, "No job found" counts as an error!
					if( ! ("No job found".equals(sc.getStderr().trim())) ){
						throw new InternalException("Unable to query jobs via bjobs: " + sc.getStderr());
					}
				}
				String bJobsOutput = sc.getStdout().trim();
				if (bJobsOutput.isEmpty()) {
					// See if any jobs have completed without being noticed
					// In practice, bjobs output is unlikely to be empty; but may be "No jobs found" or similar
					// (but that may be on stderr, see above)
					cleanUpJobs( poolUserId );
					continue;
				}
	
				Bjobs bJobs = new Bjobs( bJobsOutput );
				Collection<Bjobs.Job> jobs = bJobs.getJobs();
				if( jobs.size() == 0 ){
					// No jobs found for this pool user, so see if we missed any becoming Completed
					cleanUpJobs( poolUserId );
					continue;
				}
				
				// Even if we do have some Bjobs, they might all be Completed,
				// in which case we can free up this pool id
				
				// NOTE: we are only using cleanUpJobs() to set the status of unfound jobs (to Complete)
				// once bjobs returns no jobs at all. It's possible that some may disappear before that
				// (but hopefully this is unlikely).
				
				int uncompletedJobs = 0;
				
				for (Bjobs.Job bjob : bJobs.getJobs()) {
					String id = bjob.getJobId();
					JobStatus status = mapStatus(bjob.getStatus());
					
					if( ! isFinished(status) ){
						uncompletedJobs++;
					}
					
					// ExecHost not recorded in LsfJob at present
					// String wn = bjob.getExecHost();
					// String workerNode = wn != null ? wn.split("/")[0] : "";
					
					LsfJob job = entityManager.find(LsfJob.class, id);
					if (job != null) {/* Log updates on portal jobs */
						
						JobStatus oldJobStatus = job.getStatus();
						if (!oldJobStatus.equals(status) ) {
							
							if( ! JobStatus.Cancelled.equals(oldJobStatus) ){
								logger.debug("Updating status of job '" + id + "' from '" + oldJobStatus
										+ "' to '" + status + "'");
								job.setStatus(status);
							} else {
								logger.debug("Job '" + id + "' is Cancelled, ignoring bjobs status (" + status + ")");
							}
							if( status.equals(JobStatus.Completed) ){
								
								// If the oldJobStatus is anything other than Cancelled (it can't be Completed here),
								// we have probably spotted the job's completion for the first time, and should move
								// the output to the glassfish job area.
								// If the job has been Cancelled, we only want to move the output the first time
								// that bjobs returns a status of Completed (mapped from EXIT).
								// If the final output folder exists, assume we've already moved the output
								
								if( (! JobStatus.Cancelled.equals(oldJobStatus)) || (! finalOutputDirExists(job)) ){
									// Job has 'just' finished, so copy job output to glassfish job area
									logger.debug("Job '" + id + "' has (just) finished.");
									moveJobOutput( job );
								}
							}
						}
					}
				}
				
				if( uncompletedJobs == 0 ){
					
					// TODO Restore pool user's files to a pristine state, in case a job has fiddled with them
					
					logger.debug("Userid " + poolUserId + " has no uncompleted jobs, so releasing it back to the pool");
					lsfUserPool.freeUser(poolUserId);
				}
			} // for activePoolUsers
		} catch (Exception e) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			e.printStackTrace(new PrintStream(baos));
			logger.error("Update of db jobs from bjobs failed (exception caught here). Class " + e.getClass() + " reports "
					+ e.getMessage() + baos.toString());
		}
	}
	
	private boolean isFinished( JobStatus status ){
		return JobStatus.Completed.equals(status) || JobStatus.Cancelled.equals(status);
	}
	
	private boolean finalOutputDirExists( LsfJob job ){
		return Files.exists(jobOutputDir.resolve(job.getId()));
	}

	/**
	 * Move any job output files from the Job's batch user to our quasi-permanent holding area,
	 * where job outputs will be stored in a job.id subfolder.
	 * 
	 * This should only be called for Jobs that have status Completed (though it may also be called
	 * for jobs that "disappear" from the LSF bjobs output, on the assumption that they have finished
	 * without us noticing).
	 * 
	 * It should only be called once per Job.
	 * 
	 * @param job
	 * @throws InternalException
	 */
	private void moveJobOutput(LsfJob job) throws InternalException {
		
		logger.debug("Moving job output for job: " + job.getId() + " for lsf user: " + job.getBatchUsername());
		
		Path path = jobOutputDir.resolve(job.getId());
		
		// Expect to have to create this folder now.
		if( ! Files.exists(path) ){
			try {
				Files.createDirectory(path);
			} catch (IOException e) {
				throw new InternalException("Could not create output folder for " + path.toString() );
			}
		}
		
		String batchUser = job.getBatchUsername();
		Path batchPath = getUserJobsOutputPath( batchUser ).resolve(job.getDirectory());
		
		// Copy files from batchPath to path

		File[] files = batchPath.toFile().listFiles();
		if( files != null ){
			for( File file : files ){
				try {
					Files.copy(file.toPath(), path.resolve(file.getName()));
				} catch (IOException e) {
					throw new InternalException("Could not copy output file " + file.getName() + ": " + e.getMessage() );
				}
			}
		}
		
		// Use the batchfile owner to remove the output files.
		
		ShellCommand sc = new ShellCommand( "ssh", "-i", getSshIdFileNameFor( batchUser ), batchUser + "@localhost", "rm", "-rf", batchPath.toString() );
		if( sc.isError() ){
			throw new InternalException( "Error when trying to remove batch user output files: " + sc.getMessage() );
		}
		
		// It might make sense to delete the batchfile now as well;
		// at present, it is retained until the user explicitly deletes the job.
		
	}

	private List<String> getActivePoolUsers() {
		List<String> poolUsers = new ArrayList<String>();
		for( List<String> members : families.values() ){
			poolUsers.addAll( members );
		}
		return poolUsers;
	}
	
	/**
	 * cleanUpJobs() is called for a poolUserId when bjobs lists no jobs for that user.
	 * It checks the set of LsfJobs for that user: if any are not known to have status Completed,
	 * we assume that they have completed without updateJobsFromBjobs() noticing, and move any output
	 * for them from the batch user to the glassfish output area.
	 * Once this has been done, the batch user can be released back to the user pool.
	 * 
	 * @param poolUserId
	 * @throws InternalException
	 */
	private void cleanUpJobs( String poolUserId ) throws InternalException{
		for (LsfJob job : entityManager.createNamedQuery(LsfJob.FIND_BY_BATCHUSERNAME, LsfJob.class)
				.setParameter("batchusername", poolUserId).getResultList()) {
			if( ! isFinished(job.getStatus()) ){
				logger.warn("Updating status of job '" + job.getId() + "' from '"
						+ job.getStatus() + "' to 'Completed' as not known to bjobs");
				job.setStatus(JobStatus.Completed);
				moveJobOutput(job);
			}
		}
		
		// And free up this userid back to the pool
		
		logger.debug("Userid " + poolUserId + " has no uncompleted jobs, so releasing it back to the pool (in cleanUpJobs)");
		lsfUserPool.freeUser(poolUserId);
	}

	/**
	 * getJoboutput() implements the RESTful method output/{jobId}. It returns an InputStream on the file (if any) that
	 * contains the specified outputType (standard or error).
	 * 
	 * The source file depends on the status of the job: if we know that the job has completed, any file is assumed to be in
	 * the glassfish holding area. If the job has completed, but we don't know that yet, the file may still be in the LSF pool
	 * user's job-specific directory. If the job has not completed, the file may be in the pool user's .lsfbatch/ folder.
	 * 
	 * @param sessionId ICAT sessionId
	 * @param jobId requested Job ID
	 * @param outputType requested output type (standard or error)
	 * @return InputStream on the requested output type
	 * @throws SessionException if the sessionId is invalid (e.g. session timed out)
	 * @throws ForbiddenException if the jobId is not associated with the session user
	 * @throws InternalException if the file exists but access fails
	 * @throws ParameterException if no file of the chosen output type can be found
	 */
	public InputStream getJobOutput(String jobId, OutputType outputType, String sessionId, String icatUrl)
			throws SessionException, ForbiddenException, InternalException, ParameterException {
		
		/* 
		 * The location of the output files depends on the (real) status of the job (which we might not know).
		 * It's possible that other threads may change the job status (and spot the change)
		 * between testing and attempting to read.  To play safe, it is better just to look for the
		 * files in each location in turn (where the suffix is chosen depending on the outputType parameter):
		 *
		 *   1. in <poolUser>/.lsbatch/*.<jobid>.{out|err} (used during job execution, removed by LSF on completion)
		 *   2. in <poolUser>/<jobOutputDir>/<jobid>.{log|err} (created by LSF on completion)
		 *   3. in <glassfishArea>/<jobid>/<jobid>.{log|err} (moved by updateJobs here when it spots the job has completed)
		 */
		
		logger.info("getJobOutput called with sessionId:" + sessionId + " jobId:" + jobId
				+ " outputType:" + outputType);
		LsfJob job = getJob(jobId, sessionId, icatUrl);
		InputStream is;

		String jobFilename = job.getId() + "." + (outputType == OutputType.STANDARD_OUTPUT ? "log" : "err");
		String batchUser = job.getBatchUsername();
		
		logger.debug("Looking for mid-execution temporary output files first...");
		
		String userBase = lsfUserPoolBaseDir;
		Path batchFolder = Paths.get(userBase).resolve(batchUser)
				.resolve(".lsbatch");

		logger.debug("Batch folder to look in: " + batchFolder.toString());

		// use ssh <poolUser> to get the output file, if it exists - glassfish has no read access
		
		final String outputFilePattern =  batchFolder.toString() + File.separator + "*." + jobId + "."
				+ (outputType == OutputType.STANDARD_OUTPUT ? "out" : "err");
		
		ShellCommand sc = new ShellCommand( "ssh", "-i", getSshIdFileNameFor(batchUser), batchUser + "@localhost", "cat", outputFilePattern );
		if( ! sc.isError() ){
			
			logger.debug("Temp file cat succeeded, so treat output as result");
			return new ByteArrayInputStream(sc.getStdout().getBytes());
		}
		
		logger.debug("Temp file not found; looking for pool user output file...");
		
		Path outputParentPath = getUserJobsOutputPath( batchUser );
		Path path = outputParentPath.resolve(job.getDirectory()).resolve(jobFilename);

		if (!Files.exists(path)) {
			
			logger.debug("Pool user output file not found, so looking in glassfish area");
			
			Path glassfishAreaPath = getGlassfishOutputAreaFor( job );
			path = glassfishAreaPath.resolve(jobFilename);
		}

		if (Files.exists(path)) {
			logger.debug("Try to create stream for " + path.toString() );
			try {
				is = Files.newInputStream(path);
			} catch (IOException e) {
				throw new InternalException(e.getClass() + " reports " + e.getMessage());
			}
			logger.debug("Returning output for " + jobId);
			return is;

		} else {
			throw new ParameterException("No output file of type " + outputType
					+ " available at the moment");
		}
	}

	private Path getGlassfishOutputAreaFor(LsfJob job) {
		// Return the path to the output area under glassfish for the given job.
		// Here, we can use the jobId
		return jobOutputDir.resolve(job.getId());
	}

	private Path getUserJobsOutputPath(String batchUsername) throws InternalException {
		String userBase = lsfUserPoolBaseDir;
		
		Path outputParentPath = Paths.get(userBase).resolve(batchUsername).resolve(lsfUserOutputDir);
		if( !Files.exists(outputParentPath)){
			createUserJobsOutputDir( batchUsername );
			outputParentPath = Paths.get(userBase).resolve(batchUsername).resolve(lsfUserOutputDir);
		}
		return outputParentPath;
	}

	private void createUserJobsOutputDir(String batchUsername) throws InternalException {
		ShellCommand sc = new ShellCommand("ssh", "-i", getSshIdFileNameFor(batchUsername), batchUsername + "@localhost", "mkdir", lsfUserOutputDir);
		if (sc.isError()) {
			throw new InternalException("Unable to create jobs output folder for user " + batchUsername + ": " + sc.getStderr());
		}
		
	}

	/**
	 * submitBatch implements the non-interactive case of the RESTful method submit.
	 * If a free LSF pool user can be found, it is bound to the supplied (ICAT) userName, and is used to submit a non-interactive bsub job based on
	 * the supplied exeutable and parameters.
	 * For the submission, a dedicated subfolder is created under the LSF pool user to which bsub's standard and error output files will be
	 * written once the job completes.
	 * The job details are added to the persistent store for access in subsequent method requests (and the scheduled method updateJobsFromBjobs()).
	 * 
	 * @param userName ICAT username
	 * @param executable name of the executable to run
	 * @param parameters list of arguments (provided to the executable on the command line)
	 * @param family Family from which pool users should be drawn for this job
	 * @return String job ID returned by bsub
	 * @throws ParameterException
	 * @throws InternalException
	 * @throws SessionException
	 */
	private String submitBatch(String userName, String executable, List<String> parameters,
			String family) throws ParameterException, InternalException, SessionException {

		if (family == null) {
			family = defaultFamily;
		}
		List<String> members = families.get(family);
		if (members == null) {
			throw new ParameterException("Specified family " + family + " is not recognised");
		}
		
		String owner = assignLsfIdFrom( family );
		String idFileName = getSshIdFileNameFor( owner );
		
		String queueName = lsfDefaultQueue;
		
		// For now, use the executable as the job name.  May want to improve on this later.
		// The executable "should not" contain whitespace,
		// but the jobName MUST NOT contain whitespace!
		
		String jobName = executable.replaceAll("\\s+","_");
		
		// Create a temporary directory for the (final) output files

		Path userJobsOutputPath = getUserJobsOutputPath(owner);

		String jobDirectoryName = createJobOutputDirectory(userJobsOutputPath, owner);
		
		/*
		 * The batch script is owned by glassfish, but should be readable by the LSF users.
		 */
		File batchScriptFile = null;
		do {
			char[] pw = new char[10];
			for (int i = 0; i < pw.length; i++) {
				pw[i] = chars.charAt(random.nextInt(chars.length()));
			}
			String batchScriptName = new String(pw) + ".sh";
			batchScriptFile = new File(jobOutputDir.toString(), batchScriptName);
		} while (batchScriptFile.exists());

		createScript(batchScriptFile, jobName, executable, parameters);
		
		// Ask bsub to write log files to our temp directory; we need the absolute path
		
		String dirStr = userJobsOutputPath.toString() + File.separator + jobDirectoryName + File.separator;
		
		ShellCommand sc = new ShellCommand("ssh", "-i", idFileName, owner + "@localhost", "bsub", "-J", jobName, "-o", dirStr+"%J.log", "-e", dirStr+"%J.err", "-q", queueName,
				batchScriptFile.getAbsolutePath());
		if (sc.isError()) {
			throw new InternalException("Unable to submit job via bsub " + sc.getStderr());
		}
		String jobId = getJobId(sc.getStdout());
		
		if( jobId == null ){
			throw new InternalException("Unable to get JobId for submitted job" );
		}

		LsfJob job = new LsfJob();
		job.setId(jobId);
		job.setExecutable(executable);
		job.setBatchUsername(owner);
		job.setBatchfileName(batchScriptFile.getAbsolutePath());
		job.setDirectory(jobDirectoryName);
		job.setUsername(userName);
		job.setSubmitDate(new Date());
		
		// Get the initial status of the job from LSF
		JobStatus status = getStatus(job);
		job.setStatus( status );
		
		// If the job has already Completed (which would be suspiciously quick), we need to move the job output
		// to where getJobOutput will expect to find it.
		
		if( JobStatus.Completed.equals(status) ){
			moveJobOutput(job);
		}
		
		entityManager.persist(job);
		
		return jobId;

	}

	private String createJobOutputDirectory(Path userJobsOutputPath, String owner) throws InternalException {
		String jobDirectoryName;
		File jobDirectory = null;
		do {
			// Generate a random name, but redo if it does already exist
			char[] pw = new char[10];
			for (int i = 0; i < pw.length; i++) {
				pw[i] = chars.charAt(random.nextInt(chars.length()));
			}
			jobDirectoryName = new String(pw);
			jobDirectory = new File(userJobsOutputPath.toString(), jobDirectoryName);
		} while (jobDirectory.exists());
		
		// Now, we have to create the folder
		
		ShellCommand sc = new ShellCommand("ssh", "-i", getSshIdFileNameFor(owner), owner + "@localhost", "mkdir", jobDirectory.getAbsolutePath());
		if (sc.isError()) {
			throw new InternalException("Unable to create job output folder for user " + owner + ": " + sc.getStderr());
		}
		
		// The job directory name is all we need now.
		
		return jobDirectoryName;
	}

	private String getSshIdFileNameFor(String owner) {
		
		// Looks like this has to be an absolute path
		
		String userBase = lsfUserPoolBaseDir;
		
		Path sshIdPath = Paths.get(userBase).resolve("glassfish").resolve(".ssh").resolve("id_rsa_"+owner);
		return sshIdPath.toString();
	}

	/**
	 * Assign an LSF user ID from the given family.
	 * Throws InternalException if there are no free user IDs available for that family.
	 * 
	 * @param username the ICAT user
	 * @param family
	 * @return
	 * @throws InternalException
	 */
	private String assignLsfIdFrom( String family ) throws InternalException{
		return lsfUserPool.getUserForFamily(family);
	}
	
	private String getJobId(String stdout) throws InternalException {
		/* 
		 * bsub output format is "LsfJob <jobId> is submitted to [default] queue <queueName>.",
		 * (including the angle brackets).
		 * Experience shows we shouldn't be too precise about the words!
		 * Original pattern was "Job <(\\d+)> is submitted to queue <(.+)>\\."
		 */
		Pattern p = Pattern.compile("Job <(\\d+)> .*");
		Matcher m = p.matcher(stdout.trim());
		if( ! m.matches() ){
			logger.debug("Unable to get JobId for submitted job: bsub output was: " + stdout);
			throw new InternalException("Unable to get JobId for submitted job");
		}
		return m.group(1);
	}

	private void createScript(File batchScriptFile, String jobName, String executable, List<String> parameters) throws InternalException {

		List<String> finalParameters = new ArrayList<String>();
		finalParameters.addAll(parameters);

		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new FileWriter(batchScriptFile));
			writeln(bw, "#!/bin/sh");
			writeln(bw, "echo $(date) - " + jobName + " starting");
			String line = executable + " "
					+ JobManagementBean.escaped(finalParameters);
			logger.debug("Exec line for " + jobName + ": " + line);
			writeln(bw, line);
			writeln(bw, "rc=$?");
			writeln(bw, "echo $(date) - " + executable + " ending with code $rc");
			
			// We can't just add 'rm -rf *' to enforce cleanup, it might remove legal job output too.
			
		} catch (IOException e) {
			throw new InternalException("Exception creating batch script: " + e.getMessage());
		} finally {
			if (bw != null) {
				try {
					bw.close();
				} catch (IOException e) {
					// Ignore it
				}
			}
		}
		// File.setExecutable() only does it for the current user, we need it to be globally executable
		// batchScriptFile.setExecutable(true);
		setFileGloballyExecutable(batchScriptFile);
	}
	
	private void setFileGloballyExecutable(File file) throws InternalException{
		
		Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
        //add owners permission
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        //add group permissions
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        //add others permissions
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
		try {
			Files.setPosixFilePermissions(file.toPath(), perms);
		} catch (IOException e) {
			throw new InternalException("Unable to set permissions on batch script: " + e.getMessage() );
		}
		
	}

	private void writeln(BufferedWriter bw, String string) throws IOException {
		bw.write(string);
		bw.newLine();
		logger.debug("Script line: " + string);
	}

	private static String sq = "\"'\"";

	static String escaped(List<String> parameters) {
		StringBuilder sb = new StringBuilder();
		for (String parameter : parameters) {
			if (sb.length() != 0) {
				sb.append(" ");
			}
			int offset = 0;
			while (true) {
				int quote = parameter.indexOf('\'', offset);
				if (quote == offset) {
					sb.append(sq);
				} else if (quote > offset) {
					sb.append("'" + parameter.substring(offset, quote) + "'" + sq);
				} else if (offset != parameter.length()) {
					sb.append("'" + parameter.substring(offset) + "'");
					break;
				} else {
					break;
				}
				offset = quote + 1;
			}
		}
		return sb.toString();
	}

	/**
	 * submitInteractive() implements the interactive case of the RESTful method submit.
	 * It is not supported at present.
	 * 
	 * @param userName
	 * @param executable
	 * @param parameters
	 * @param family
	 * @return
	 * @throws InternalException
	 * @throws ParameterException
	 */
	private String submitInteractive(String userName, String executable, List<String> parameters,
			String family) throws InternalException, ParameterException {
		throw new ParameterException("Interactive jobs are not currently supported by LsfBatch");
		// TODO get interactive sessions working on Platform LSF
	}

	private String getUserName(String sessionId, String icatUrl) throws SessionException, ParameterException {
		try {
			checkCredentials(sessionId, icatUrl);
			ICATService service = new ICATService(new URL(new URL(icatUrl), "ICATService/ICAT?wsdl"), qName);
			return service.getICATPort().getUserName(sessionId);
		} catch (IcatException_Exception e) {
			if (e.getFaultInfo().getType() == IcatExceptionType.SESSION) {
				throw new SessionException("IcatException " + e.getFaultInfo().getType() + " " + e.getMessage());
			} else {
				throw new ParameterException("IcatException " + e.getFaultInfo().getType() + " " + e.getMessage());
			}
		} catch (MalformedURLException e) {
			throw new ParameterException("Bad URL " + e.getMessage());
		}
	}

	/**
	 * List the ids of those jobs owned by the session user.
	 * 
	 * @param sessionId
	 * @param icatUrl
	 * @return Json array of job-ids
	 * @throws SessionException
	 * @throws ParameterException
	 * @throws InternalException
	 */
	public String list(String sessionId, String icatUrl) throws SessionException,
			ParameterException, InternalException {
		logger.info("listStatus called with sessionId:" + sessionId);

		String username = getUserName(sessionId, icatUrl);
		List<String> jobs = entityManager
				.createNamedQuery(LsfJob.ID_BY_USERNAME, String.class)
				.setParameter("username", username).getResultList();
		return BatchJson.list(jobs);
	}

	/**
	 * getStatus(jobId,...) implements the RESTful method status/{jobId}.
	 * It returns a Json string containing the status and other attributes of the supplied jobId.
	 * 
	 * @param jobId
	 * @param sessionId
	 * @param icatUrl
	 * @return
	 * @throws SessionException
	 * @throws ForbiddenException
	 * @throws ParameterException
	 * @throws InternalException
	 */
	public String getStatus(String jobId, String sessionId, String icatUrl) throws SessionException,
			ForbiddenException, ParameterException, InternalException {
		logger.info("getStatus called with sessionId:" + sessionId + " jobId:" + jobId);
		LsfJob job = getJob(jobId, sessionId, icatUrl);
		
		JobStatus status = getStatus( job );
		
		return BatchJson.getStatus(status);
	}
	
	/**
	 * Returns the "best/last" known status of the given job. 
	 * If bjobs has an entry for the job, it uses the returned status value
	 * (unless the LsfJob's status is Cancelled, which takes priority). 
	 * If no bjobs entry is found, the LsfJob's current status is returned.
	 * 
	 * @param job
	 * @return
	 * @throws InternalException
	 */
	private JobStatus getStatus( LsfJob job ) throws InternalException{
		
		String jobId = job.getId();
		JobStatus oldStatus = job.getStatus();
		String owner = job.getBatchUsername();
		
		JobStatus status;
		
		logger.debug("job " + jobId + " is being run by " + owner);

		String idFileName = getSshIdFileNameFor( owner );

		ShellCommand sc = new ShellCommand("ssh", "-i", idFileName, owner + "@localhost", "bjobs", "-aw", jobId);
		if (sc.isError()) {
			// Astonishingly, "No job found" counts as an error!
			// ... as does "Job <jobId> is not found", which is what actually appears
			String expectedMessage = "Job <" + jobId + "> is not found";
			if( ! expectedMessage.equals(sc.getStderr().trim())){
				throw new InternalException("Unable to query job (id " + jobId
						+ ") via bjobs: " + sc.getStderr());
			}
		}
		
		Bjobs bjobs = new Bjobs( sc.getStdout() );
		Bjobs.Job bjob = bjobs.getJob(jobId);
		if( bjob == null ){
			logger.debug("Unable to find job (id " + jobId + ") in bjobs; return job's last known status (" + oldStatus + ")");
			status = oldStatus;
		} else {
			status = mapStatus( bjob.getStatus() );
			if( JobStatus.Cancelled.equals(oldStatus) ){
				logger.debug("Job (id " + jobId + ") has status Cancelled; return this rather than bjobs' status (" + status + ")");
				status = oldStatus;
			}
		}
		
		return status;
	}

	/**
	 * Convert the "low-level" status values returned by bjobs into broad status values that we report back
	 * 
	 * @param status the bjobs status value
	 * @return mapped JobStatus value - one of Completed, Executing, Queued, Held, Unknown
	 */
	private JobStatus mapStatus(String status) {

		JobStatus outStatus = JobStatus.Unknown;
		
		if( "PEND".equals(status) || "WAIT".equals(status) ){
			outStatus = JobStatus.Queued;
		} else if( "DONE".equals(status) || "EXIT".equals(status) ){
			outStatus = JobStatus.Completed;
		} else if( "RUN".equals(status) ){
			outStatus = JobStatus.Executing;
		} else if( "PSUSP".equals(status) || "USUSP".equals(status) || "SSUSP".equals(status) ){
			outStatus = JobStatus.Held;
		} else if( "UNKWN".equals(status) || "ZOMBI".equals(status) ){
			// These are the "known unknowns" :-)
			outStatus = JobStatus.Unknown;
		}
		return outStatus;
	}

	private LsfJob getJob(String jobId, String sessionId, String icatUrl) throws SessionException, ForbiddenException,
			ParameterException {
		String username = getUserName(sessionId, icatUrl);
		if (jobId == null) {
			throw new ParameterException("No jobId was specified");
		}
		LsfJob job = entityManager.find(LsfJob.class, jobId);
		if (job == null || !job.getUsername().equals(username)) {
			throw new ForbiddenException("Job does not belong to you");
		}
		return job;
	}

	/**
	 * delete() implements the RESTful method delete/{jobId}.
	 * The specified job must have completed (including by being cancelled).
	 * It removes any job output and deletes the job from the persistent store.
	 * We assume that the associated pool user can now be released back to the pool
	 * (this assumes that each pool user only runs a single job at a time).
	 * 
	 * @param jobId
	 * @param sessionId
	 * @param icatUrl
	 * @throws SessionException
	 * @throws ForbiddenException
	 * @throws InternalException
	 * @throws ParameterException
	 */
	public void delete(String jobId, String sessionId, String icatUrl) throws SessionException, ForbiddenException,
			InternalException, ParameterException {
		
		logger.info("delete called with sessionId:" + sessionId + " jobId:" + jobId);
		LsfJob job = getJob(jobId, sessionId, icatUrl);
		String owner = job.getBatchUsername();
		logger.debug("job " + jobId + " is being run by " + owner);
		
		ShellCommand sc;
		
		// If we don't know that the Job has Completed, check the current status, if we can
		
		if( ! isFinished(job.getStatus())){
			// Get the status of this job.
			
			String idFileName = getSshIdFileNameFor( owner );
	
			sc = new ShellCommand("ssh", "-i", idFileName, owner + "@localhost", "bjobs", "-aw", jobId);
			if (sc.isError()) {
				throw new InternalException("Unable to query job (id " + jobId
						+ ") via bjobs: " + sc.getStderr());
			}
			
			Bjobs bjobs = new Bjobs( sc.getStdout() );
			Bjobs.Job bjob = bjobs.getJob(jobId);
			if( bjob != null ){
				
				// Check whether the job has actually finished
			
				JobStatus status = mapStatus( bjob.getStatus() );
				logger.debug("Status is " + status);
				if (! isFinished(status)) {
					throw new ParameterException("LsfJob " + jobId + " is " + status);
				}
			} else {
				logger.debug("Delete called for job with supposed status " + job.getStatus() + ", but not found in bjobs, so assume it has Completed.");
			}
		}

		entityManager.remove(job);
		
		// NOTE: we assume each owner has at most one current job
		// owner may already be free, if we spot when the job completes
		
		lsfUserPool.freeUser(owner);

		try {
			Path dir = getUserJobsOutputPath(owner).resolve(job.getDirectory());
			
			// And get the owner to remove everything under there - if it hasn't been removed already
			
			if( Files.exists(dir)) {
				sc = new ShellCommand("ssh", "-i", getSshIdFileNameFor(owner), owner + "@localhost", "rm", "-rf", dir.toString());
				if (sc.isError()) {
					throw new InternalException("Unable to delete job output folder for user " + owner + ": " + sc.getStderr());
				}
			}
			
			// Remove the job output dir in glassfish
			
			dir = getGlassfishOutputAreaFor( job );
			File[] files = dir.toFile().listFiles();
			if (files != null) {
				for (File f : dir.toFile().listFiles()) {
					Files.delete(f.toPath());
				}
				Files.delete(dir);
				logger.debug("Directory " + dir + " has been deleted");
			}
			
			// And remove the batchfile
			// (Alternative: remove it once the job has Completed?)
			
			Files.deleteIfExists(Paths.get(job.getBatchfileName()));
			
		} catch (IOException e) {
			throw new InternalException("Unable to delete jobOutputDirectory "
					+ job.getDirectory());
		}

	}

	/**
	 * cancel() implements the RESTful method cancel/{jobId}.
	 * It uses the Platform LSF bkill command to kill the specified job, and sets the job status to Cancelled.
	 * It does not remove any existing job output, nor does it clean up or release the pool user;
	 * we rely on a subsequent delete request or some future scheduled run of updateJobsFromBjobs
	 * to do that.
	 * 
	 * @param jobId
	 * @param sessionId
	 * @param icatUrl
	 * @throws SessionException
	 * @throws ForbiddenException
	 * @throws InternalException
	 * @throws ParameterException
	 */
	public void cancel(String jobId, String sessionId, String icatUrl) throws SessionException, ForbiddenException,
			InternalException, ParameterException {
		
		logger.info("cancel called with sessionId:" + sessionId + " jobId:" + jobId);
		LsfJob job = getJob(jobId, sessionId, icatUrl);
		String owner = job.getBatchUsername();
		logger.debug("job " + jobId + " is being run by " + owner);
		String idFileName = getSshIdFileNameFor( owner );
		ShellCommand sc = new ShellCommand("ssh", "-i", idFileName, owner + "@localhost", "bkill", job.getId());
		if (sc.isError() && !sc.getStderr().startsWith("Warning")) {
			throw new ParameterException("Unable to cancel job " + job.getId() + ": " + sc.getStderr());
		}
		logger.debug("Setting status of job " + jobId + " to Cancelled");
		job.setStatus(JobStatus.Cancelled);
	}

	private void checkCredentials(String sessionId, String icatUrl) throws ParameterException {
		if (sessionId == null) {
			throw new ParameterException("No sessionId was specified");
		}
		if (icatUrl == null) {
			throw new ParameterException("No ICAT url was specified");
		}
	}

	/**
	 * submit() implements the RESTful method submit.
	 * Note that only batch submission is available at present.
	 * 
	 * @param executable
	 * @param parameters
	 * @param family
	 * @param interactive
	 * @param sessionId
	 * @param icatUrl
	 * @return
	 * @throws InternalException
	 * @throws SessionException
	 * @throws ParameterException
	 */
	public String submit(String executable, List<String> parameters,
			String family, boolean interactive, String sessionId, String icatUrl) throws InternalException, SessionException,
			ParameterException {
		logger.info("submit called with sessionId:" + sessionId + " executable:" + executable
				+ " parameters:" + parameters + " family:" + family + " :" + " interactive:"
				+ interactive);
		String userName = getUserName(sessionId, icatUrl);
		String jobId;
		if (interactive) {
			jobId = submitInteractive(userName, executable, parameters, family);
		} else {
			jobId = submitBatch(userName, executable, parameters, family);
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		JsonGenerator gen = Json.createGenerator(baos);
		gen.writeStartObject().write("jobId", jobId).writeEnd().close();
		return baos.toString();
	}

	/**
	 * estimate() implements the RESTful method estimate.
	 * It takes the same parameters as the submit method, and returns a Json string
	 * containing a "time" field.
	 * At present, only batch jobs are supported; further, the estimate is wildly optimistic!
	 * 
	 * @param executable
	 * @param parameters
	 * @param family
	 * @param interactive
	 * @param sessionId
	 * @param icatUrl
	 * @return
	 * @throws SessionException
	 * @throws ParameterException
	 */
	public String estimate(String executable, List<String> parameters,
			String family, boolean interactive, String sessionId, String icatUrl) throws SessionException, ParameterException {
		String userName = getUserName(sessionId, icatUrl);

		int time;
		if (interactive) {
			time = estimateInteractive(userName, executable, parameters, family);
		} else {
			time = estimateBatch(userName, executable, parameters, family);
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		JsonGenerator gen = Json.createGenerator(baos);
		gen.writeStartObject().write("time", time).writeEnd().close();
		return baos.toString();
	}

	private int estimateBatch(String userName, String executable, List<String> parameters,
			String family) {
		return 0;
	}

	private int estimateInteractive(String userName, String executable, List<String> parameters,
			String family) throws SessionException, ParameterException {
		throw new ParameterException("Interactive jobs are not currently supported by lsfbatch");
	}

}
