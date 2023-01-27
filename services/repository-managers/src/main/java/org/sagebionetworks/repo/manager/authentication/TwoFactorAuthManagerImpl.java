package org.sagebionetworks.repo.manager.authentication;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.token.TokenGenerator;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.TotpSecret;
import org.sagebionetworks.repo.model.auth.TotpSecretActivationRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthOtpType;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthStatus;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthToken;
import org.sagebionetworks.repo.model.auth.TwoFactorState;
import org.sagebionetworks.repo.model.dbo.otp.DBOOtpSecret;
import org.sagebionetworks.repo.model.dbo.otp.OtpSecretDao;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.securitytools.AESEncryptionUtils;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class TwoFactorAuthManagerImpl implements TwoFactorAuthManager {

	public static final long TWO_FA_TOKEN_DURATION_MINS = 10;
	
	private TotpManager totpMananger;
	private OtpSecretDao otpDao;
	private TokenGenerator tokenGenerator;
	private StackConfiguration config;
	private Clock clock;

	public TwoFactorAuthManagerImpl(TotpManager totpManager, OtpSecretDao otpDao, TokenGenerator tokenGenerator, StackConfiguration config, Clock clock) {
		this.totpMananger = totpManager;
		this.otpDao = otpDao;
		this.tokenGenerator = tokenGenerator;
		this.config = config;
		this.clock = clock;
	}

	@Override
	@WriteTransaction
	public TotpSecret init2Fa(UserInfo user) {		
		assertValidUser(user);
		
		String unencryptedSecret = totpMananger.generateTotpSecret();
		
		String userEncryptionKey = getUserEncryptionKey(user);
		
		String encryptedSecret = AESEncryptionUtils.encryptWithAESGCM(unencryptedSecret, userEncryptionKey);
		
		Long secretId = otpDao.storeSecret(user.getId(), encryptedSecret).getId();

		return new TotpSecret()
			.setSecretId(secretId.toString())
			.setSecret(unencryptedSecret)
			.setAlg(TotpManager.HASH_ALG.getFriendlyName())
			.setDigits(Long.valueOf(TotpManager.DIGITS_COUNT))
			.setPeriod(Long.valueOf(TotpManager.PERIOD));
	}

	@Override
	@WriteTransaction
	public void enable2Fa(UserInfo user, TotpSecretActivationRequest request) {
		assertValidUser(user);
		
		ValidateArgument.required(request, "The request");
		ValidateArgument.required(request.getSecretId(), "The secret id");
		ValidateArgument.required(request.getTotp(), "The totp code");
		
		Long userId = user.getId();
		Long secretId = Long.valueOf(request.getSecretId());
		
		DBOOtpSecret secret = otpDao.getSecret(userId, secretId)
			.orElseThrow(() -> new UnauthorizedException("Invalid secret id"));
		
		if (secret.getActive()) {
			throw new IllegalArgumentException("Two factor authentication is already enabled with this secret");
		}
		
		if (!isTotpValid(user, secret, request.getTotp())) {
			throw new IllegalArgumentException("Invalid totp code");
		}
		
		// If the user has a secret already in use, delete it first
		otpDao.getActiveSecret(userId).ifPresent( existingSecret -> otpDao.deleteSecret(userId, existingSecret.getId()));
		otpDao.activateSecret(userId, secretId);
	}

	@Override
	public TwoFactorAuthStatus get2FaStatus(UserInfo user) {
		assertValidUser(user);
		
		TwoFactorState state = otpDao.hasActiveSecret(user.getId()) ? TwoFactorState.ENABLED : TwoFactorState.DISABLED;
		
		return new TwoFactorAuthStatus().setStatus(state);
	}

	@Override
	@WriteTransaction
	public void disable2Fa(UserInfo user) {
		assertValidUser(user);
		
		if (!otpDao.hasActiveSecret(user.getId())) {
			throw new IllegalArgumentException("Two factor authentication is not enabled");
		}
		
		otpDao.deleteSecrets(user.getId());
	}
	
	@Override
	public boolean is2FaCodeValid(UserInfo user, TwoFactorAuthOtpType otpType, String otpCode) {
		assertValidUser(user);
		
		ValidateArgument.required(otpType, "The otpType");
		ValidateArgument.requiredNotBlank(otpCode, "The otpCode");
		
		DBOOtpSecret secret = otpDao.getActiveSecret(user.getId()).orElseThrow(() -> new IllegalArgumentException("Two factor authentication is not enabled"));
		
		switch (otpType) {
		case TOTP:
			return isTotpValid(user, secret, otpCode);
		default:
			throw new UnsupportedOperationException("2FA code type " + otpType + " not supported yet.");
		}
		
	}
	
	@Override
	public String generate2FaLoginToken(UserInfo user) {
		assertValidUser(user);
		
		Date now = clock.now();
		Date tokenExpiration = Date.from(now.toInstant().plus(TWO_FA_TOKEN_DURATION_MINS, ChronoUnit.MINUTES)); 
		
		TwoFactorAuthToken token = new TwoFactorAuthToken()
			.setUserId(user.getId())
			.setCreatedOn(now)
			.setExpiresOn(tokenExpiration);
		
		tokenGenerator.signToken(token);
		
		try {
			String tokenJson = EntityFactory.createJSONStringForEntity(token);
			return new String(Base64.getEncoder().encode(tokenJson.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
		} catch (JSONObjectAdapterException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	public boolean is2FaLoginTokenValid(UserInfo user, String encodedToken) {
		assertValidUser(user);
		
		ValidateArgument.requiredNotBlank(encodedToken, "The token");
		
		try {
			String decodedToken = new String(Base64.getDecoder().decode(encodedToken.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
			TwoFactorAuthToken token = EntityFactory.createEntityFromJSONString(decodedToken, TwoFactorAuthToken.class);
			
			if(!user.getId().equals(token.getUserId())) {
				return false;
			}
			
			tokenGenerator.validateToken(token);
		} catch (JSONObjectAdapterException | UnauthorizedException | IllegalArgumentException e) {
			return false;
		}
		return true;
	}
	
	boolean isTotpValid(UserInfo user, DBOOtpSecret secret, String otpCode) {
		String encryptedSecret = secret.getSecret();
		
		String userEncryptionKey = getUserEncryptionKey(user); 
		
		String unencryptedSecret = AESEncryptionUtils.decryptWithAESGCM(encryptedSecret, userEncryptionKey);
		
		return totpMananger.isTotpValid(unencryptedSecret, otpCode);
	}
	
	/**
	 * @param user
	 * @return User encryption key derived from a password. Uses the user id as the salt. 
	 */
	String getUserEncryptionKey(UserInfo user) {
		return AESEncryptionUtils.newSecretKeyFromPassword(config.getOtpSecretsPassword(), user.getId().toString());
	}

	void assertValidUser(UserInfo user) {
		ValidateArgument.required(user, "The user");
		if (AuthorizationUtils.isUserAnonymous(user)) {
			throw new UnauthorizedException("You need to authenticate to perform this action");
		}
	}
}
