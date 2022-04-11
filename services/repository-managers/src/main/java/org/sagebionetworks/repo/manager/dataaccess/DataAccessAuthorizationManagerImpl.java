package org.sagebionetworks.repo.manager.dataaccess;

import org.sagebionetworks.repo.manager.UserProfileManager;
import org.sagebionetworks.repo.manager.verification.VerificationHelper;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dataaccess.RequestInterface;
import org.sagebionetworks.repo.model.dataaccess.Submission;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.RequestDAO;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.SubmissionDAO;
import org.sagebionetworks.repo.model.verification.VerificationSubmission;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DataAccessAuthorizationManagerImpl implements DataAccessAuthorizationManager {
	
	private UserProfileManager userProfileManager;
	
	private AccessControlListDAO aclDao;
	
	private RequestDAO requestDao;
	
	private SubmissionDAO submissionDao;

	@Autowired
	public DataAccessAuthorizationManagerImpl(UserProfileManager userProfileManager, AccessControlListDAO aclDao, RequestDAO requestDao, SubmissionDAO submissionDao) {
		this.userProfileManager = userProfileManager;
		this.aclDao = aclDao;
		this.requestDao = requestDao;
		this.submissionDao = submissionDao;
	}
	
	@Override
	public AuthorizationStatus canDownloadRequestFiles(UserInfo userInfo, String requestId) {
		ValidateArgument.required(userInfo, "userInfo");
		ValidateArgument.required(requestId, "requestId");
		
		RequestInterface request = requestDao.get(requestId);
		
		return checkDownloadAccessForAccessRequirement(userInfo, request.getAccessRequirementId());
	}
	

	@Override
	public AuthorizationStatus canDownloadSubmissionFiles(UserInfo userInfo, String submissionId) {
		ValidateArgument.required(userInfo, "userInfo");
		ValidateArgument.required(submissionId, "submissionId");
		
		Submission submission = submissionDao.getSubmission(submissionId);
		
		return checkDownloadAccessForAccessRequirement(userInfo, submission.getAccessRequirementId());
	}
	
	AuthorizationStatus checkDownloadAccessForAccessRequirement(UserInfo userInfo, String accessRequirementId) {
		AuthorizationStatus reviewerStatus = canReviewSubmissions(userInfo, accessRequirementId);
		
		if (reviewerStatus.isAuthorized()) {
			return reviewerStatus;
		}
		
		return AuthorizationStatus.accessDenied("The user does not have download access.");
	}
	
	@Override
	public AuthorizationStatus canReviewSubmissions(UserInfo userInfo, String accessRequirementId) {
		ValidateArgument.required(userInfo, "userInfo");
		ValidateArgument.required(accessRequirementId, "accessRequirementId");
		
		if (AuthorizationUtils.isACTTeamMemberOrAdmin(userInfo)) {
			return AuthorizationStatus.authorized();
		}
		
		if (!aclDao.canAccess(userInfo, accessRequirementId, ObjectType.ACCESS_REQUIREMENT, ACCESS_TYPE.REVIEW_SUBMISSIONS).isAuthorized()) {
			return AuthorizationStatus.accessDenied(String.format("The user does not have permissions to review data access submissions for access requirement %s.", accessRequirementId));
		} 
		
		// Only validated users can review submissions
		VerificationSubmission currentVerification = userProfileManager.getCurrentVerificationSubmission(userInfo.getId());
				
		if (!VerificationHelper.isVerified(currentVerification)) {
			return AuthorizationStatus.accessDenied("The user must be validated in order to review data access submissions.");
		}
		
		return AuthorizationStatus.authorized();		
	}
	

}
