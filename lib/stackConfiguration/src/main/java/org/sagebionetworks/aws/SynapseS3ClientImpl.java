package org.sagebionetworks.aws;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.map.PassiveExpiringMap;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.BucketCrossOriginConfiguration;
import com.amazonaws.services.s3.model.BucketWebsiteConfiguration;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.StringUtils;

/*
 * 
 * This is a facade for AmazonS3 (Amazon's S3 Client), exposing just the methods used by Synapse
 * and, in each method, doing the job of figuring out which region the given bucket is in, 
 * so that the S3 Client for that region is used.
 * 
 */
public class SynapseS3ClientImpl implements SynapseS3Client {

	private Map<Region, AmazonS3> regionSpecificClients;

	private Map<String, Region> bucketLocation;

	public SynapseS3ClientImpl(Map<Region, AmazonS3> regionSpecificClients) {
		this.regionSpecificClients=regionSpecificClients;
		bucketLocation = Collections.synchronizedMap(new PassiveExpiringMap<>(1, TimeUnit.HOURS));
	}
	
	public Region getRegionForBucket(String bucketName) {
		String location = null;
		try {
			location = getUSStandardAmazonClient().getBucketLocation(bucketName);
		}  catch (com.amazonaws.services.s3.model.AmazonS3Exception e) {
			throw new CannotDetermineBucketLocationException("Failed to determine the Amazon region for bucket '"+bucketName+
					"'. Please ensure that the bucket's policy grants 's3:GetBucketLocation' permission to Synapse.", e);
		}
		Region result = null;
		if (StringUtils.isNullOrEmpty(location)) {
			result = Region.US_Standard;
		} else {
			result =  Region.fromValue(location);	
		}
		return result;
	}

	public Region getRegionForBucketOrAssumeUSStandard(String bucketName) {
		if (StringUtils.isNullOrEmpty(bucketName)) throw new IllegalArgumentException("bucketName is required.");
		Region result = bucketLocation.get(bucketName);
		if (result!=null) return result;
		try {
			result = getRegionForBucket(bucketName);
		} catch(CannotDetermineBucketLocationException e) {
			// The downside of doing this is that if we assume the wrong region then we
			// may generate invalid presigned URLs.
			//
			// This should be viewed as a temporary 'fix' until we ensure that permissions on
			// legacy private buckets are updated to grant S3:GetBucketLocation permission to Synapse
			// 
			result = Region.US_Standard;
		}
		bucketLocation.put(bucketName, result);
		return result;
	}

	public AmazonS3 getS3ClientForBucketOrAssumeUSStandard(String bucket) {
		Region region = getRegionForBucketOrAssumeUSStandard(bucket);
		return regionSpecificClients.get(region);
	}

	@Override
	public AmazonS3 getUSStandardAmazonClient() {
		return regionSpecificClients.get(Region.US_Standard);
	}

	@Override
	public ObjectMetadata getObjectMetadata(String bucketName, String key)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).getObjectMetadata(bucketName, key);
	}

	@Override
	public void deleteObject(String bucketName, String key) throws SdkClientException, AmazonServiceException {
		getS3ClientForBucketOrAssumeUSStandard(bucketName).deleteObject( bucketName,  key);
	}

	@Override
	public DeleteObjectsResult deleteObjects(DeleteObjectsRequest deleteObjectsRequest)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(deleteObjectsRequest.getBucketName()).deleteObjects(deleteObjectsRequest);
	}

	@Override
	public PutObjectResult putObject(String bucketName, String key, InputStream input, ObjectMetadata metadata)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).putObject(bucketName,  key,  input,  metadata);
	}

	@Override
	public PutObjectResult putObject(String bucketName, String key, File file)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).putObject( bucketName, key,  file);
	}

	@Override
	public PutObjectResult putObject(PutObjectRequest putObjectRequest)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(putObjectRequest.getBucketName()).putObject( putObjectRequest);
	}

	@Override
	public S3Object getObject(String bucketName, String key) throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).getObject( bucketName,  key);
	}

	@Override
	public S3Object getObject(GetObjectRequest getObjectRequest) throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(getObjectRequest.getBucketName()).getObject(getObjectRequest);
	}

	@Override
	public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File destinationFile)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(getObjectRequest.getBucketName()).getObject( getObjectRequest,  destinationFile);
	}

	@Override
	public ObjectListing listObjects(String bucketName, String prefix)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).listObjects( bucketName,  prefix);
	}

	@Override
	public ObjectListing listObjects(ListObjectsRequest listObjectsRequest)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(listObjectsRequest.getBucketName()).listObjects( listObjectsRequest);
	}

	@Override
	public Bucket createBucket(String bucketName) throws SdkClientException, AmazonServiceException {
		return getUSStandardAmazonClient().createBucket(bucketName);
	}

	@Override
	public boolean doesObjectExist(String bucketName, String objectName)
			throws AmazonServiceException, SdkClientException {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).doesObjectExist( bucketName,  objectName);
	}

	@Override
	public void setBucketCrossOriginConfiguration(String bucketName,
			BucketCrossOriginConfiguration bucketCrossOriginConfiguration) {
		getS3ClientForBucketOrAssumeUSStandard(bucketName).setBucketCrossOriginConfiguration( bucketName,
				bucketCrossOriginConfiguration);
	}

	@Override
	public URL generatePresignedUrl(GeneratePresignedUrlRequest generatePresignedUrlRequest) throws SdkClientException {
		return getS3ClientForBucketOrAssumeUSStandard(generatePresignedUrlRequest.getBucketName()).generatePresignedUrl( generatePresignedUrlRequest);
	}

	@Override
	public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(request.getBucketName()).initiateMultipartUpload(request);
	}

	@Override
	public CopyPartResult copyPart(CopyPartRequest copyPartRequest) throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(copyPartRequest.getDestinationBucketName()).copyPart(copyPartRequest);
	}

	@Override
	public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest request)
			throws SdkClientException, AmazonServiceException {
		return getS3ClientForBucketOrAssumeUSStandard(request.getBucketName()).completeMultipartUpload(request);
	}

	@Override
	public void setBucketWebsiteConfiguration(String bucketName, BucketWebsiteConfiguration configuration)
			throws SdkClientException, AmazonServiceException {
		getS3ClientForBucketOrAssumeUSStandard(bucketName).setBucketWebsiteConfiguration(bucketName,  configuration);
	}

	@Override
	public void setBucketPolicy(String bucketName, String policyText)
			throws SdkClientException, AmazonServiceException {
		getS3ClientForBucketOrAssumeUSStandard(bucketName).setBucketPolicy(bucketName,  policyText);
	}

	@Override
	public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration(String bucketName) {
		return getS3ClientForBucketOrAssumeUSStandard(bucketName).getBucketCrossOriginConfiguration(bucketName);
	}
}
