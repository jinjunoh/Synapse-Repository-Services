package org.sagebionetworks.annotations.worker;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sagebionetworks.asynchronous.workers.sqs.MessageUtils;
import org.sagebionetworks.cloudwatch.WorkerLogger;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.SubmissionStatusAnnotationsAsyncManager;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.springframework.dao.DeadlockLoserDataAccessException;

import com.amazonaws.services.sqs.model.Message;

/**
 * The worker that processes messages for SubmissionStatus Annotations jobs.
 *
 */
public class AnnotationsWorker implements Callable<List<Message>> {
	
	static private Log log = LogFactory.getLog(AnnotationsWorker.class);
	
	List<Message> messages;
	SubmissionStatusAnnotationsAsyncManager ssAsyncMgr;
	WorkerLogger workerLogger;

	public AnnotationsWorker(List<Message> messages, SubmissionStatusAnnotationsAsyncManager ssAsyncMgr, WorkerLogger workerProfiler) {
		if(messages == null) throw new IllegalArgumentException("Messages cannot be null");
		if(ssAsyncMgr == null) throw new IllegalArgumentException("Asynchronous DAO cannot be null");
		if (workerProfiler == null) throw new IllegalArgumentException("workerProfiler cannot be null");
		this.messages = messages;
		this.ssAsyncMgr = ssAsyncMgr;
		this.workerLogger = workerProfiler;
	}

	@Override
	public List<Message> call() throws Exception {
		List<Message> processedMessages = new LinkedList<Message>();
		// process each message
		for (Message message: messages) {
			// Extract the ChangeMessage
			ChangeMessage change = MessageUtils.extractMessageBody(message);
			// We only care about Submission messages here
				try {
					if (ObjectType.SUBMISSION == change.getObjectType()) {
						// Is this a create, update, or delete?
						if (ChangeType.CREATE == change.getChangeType()) {
							ssAsyncMgr.createSubmissionStatus(change.getObjectId());
						} else if (ChangeType.UPDATE == change.getChangeType()) {
							// update
							ssAsyncMgr.updateSubmissionStatus(change.getObjectId());
						} else if(ChangeType.DELETE == change.getChangeType()) {
							// delete
							ssAsyncMgr.deleteSubmission(change.getObjectId());
						} else {
							throw new IllegalArgumentException("Unknown change type: "+change.getChangeType());
						}
					} else if (ObjectType.EVALUATION == change.getObjectType()) {
						// Is this a create, update, or delete?
						if (ChangeType.CREATE == change.getChangeType()) {
							ssAsyncMgr.createEvaluationSubmissionStatuses(change.getObjectId());
						} else if (ChangeType.UPDATE == change.getChangeType()) {
							// update
							ssAsyncMgr.updateEvaluationSubmissionStatuses(change.getObjectId());
						} else if(ChangeType.DELETE == change.getChangeType()) {
							// delete
							ssAsyncMgr.deleteEvaluationSubmissions(change.getObjectId());
						} else {
							throw new IllegalArgumentException("Unknown change type: "+change.getChangeType());
						}
					}
					// this line is reached both (1) for successfully processed messages and (2) for unprocessed messages
					processedMessages.add(message);
				} catch (DeadlockLoserDataAccessException e) {
					log.info("Intermittent error in AnnotationsWorker: "+e.getMessage()+". Will retry");
					workerLogger.logWorkerFailure(this.getClass(), change, e, true);
				} catch (Throwable e) {
					// Something went wrong and we did not process the message.  By default we remove the message from the queue.
					log.error("Failed to process message", e);
					processedMessages.add(message);
					workerLogger.logWorkerFailure(this.getClass(), change, e, false);
				}
		}
		return processedMessages;
	}

}
