/*
   Copyright 2018-2020 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.filesystem;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.internal.BucketNameUtils;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.DeleteBucketRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.RestoreObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.model.Tier;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.SenderException;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.util.CredentialFactory;

public class AmazonS3FileSystem extends FileSystemBase<S3Object> implements IWritableFileSystem<S3Object> {

	public static final List<String> AVAILABLE_REGIONS = getAvailableRegions();
	public static final List<String> STORAGE_CLASSES = getStorageClasses();
	public static final List<String> TIERS = getTiers();

	private String accessKey;
	private String secretKey;
	private String authAlias;

	private AmazonS3 s3Client;
	private boolean chunkedEncodingDisabled = false;
	private boolean forceGlobalBucketAccessEnabled = false;
	private String clientRegion = Regions.EU_WEST_1.getName();
	
	private String bucketName;
	private String destinationBucketName;
	private String bucketRegion;

	private String storageClass;
	private String tier = Tier.Standard.toString();
	private int expirationInDays = -1;

	private boolean storageClassEnabled = false;
	private boolean bucketCreationEnabled = false;
	private boolean bucketExistsThrowException = true;
	
	private String proxyHost = null;
	private Integer proxyPort = null;
	
	@Override
	public void configure() throws ConfigurationException {

		if (StringUtils.isEmpty(getAccessKey()) || StringUtils.isEmpty(getSecretKey()))
			throw new ConfigurationException(" empty credential fields, please prodive aws credentials");

		if (StringUtils.isEmpty(getClientRegion()) || !AVAILABLE_REGIONS.contains(getClientRegion()))
			throw new ConfigurationException(" invalid region [" + getClientRegion() + "] please use one of the following supported regions " + AVAILABLE_REGIONS.toString());

		if (StringUtils.isEmpty(getBucketName()) || !BucketNameUtils.isValidV2BucketName(getBucketName()))
			throw new ConfigurationException(" invalid or empty bucketName [" + getBucketName() + "] please visit AWS to see correct bucket naming");
	}

	@Override
	public void open() throws FileSystemException {
		CredentialFactory cf = new CredentialFactory(getAuthAlias(), getAccessKey(), getSecretKey());
		BasicAWSCredentials awsCreds = new BasicAWSCredentials(cf.getUsername(), cf.getPassword());
		AmazonS3ClientBuilder s3ClientBuilder = AmazonS3ClientBuilder.standard()
				.withChunkedEncodingDisabled(isChunkedEncodingDisabled())
				.withForceGlobalBucketAccessEnabled(isForceGlobalBucketAccessEnabled()).withRegion(getClientRegion())
				.withCredentials(new AWSStaticCredentialsProvider(awsCreds))
				.withClientConfiguration(this.getProxyConfig());
		s3Client = s3ClientBuilder.build();
		super.open();
	}

	@Override
	public void close() throws FileSystemException {
		super.close();
		if(s3Client != null) {
			s3Client.shutdown();
		}
	}

	@Override
	public S3Object toFile(String filename) throws FileSystemException {
		S3Object object = new S3Object();
		object.setKey(filename);
		return object;
	}

	@Override
	public S3Object toFile(String folder, String filename) throws FileSystemException {
		return toFile(folder+"/"+filename);
	}


	@Override
	public int getNumberOfFilesInFolder(String folder) throws FileSystemException {
		List<S3ObjectSummary> summaries = null;
		String prefix = folder != null ? folder + "/" : "";
		try {
			ObjectListing listing = s3Client.listObjects(bucketName, prefix);
			summaries = listing.getObjectSummaries();
			int result = summaries.size();
			while (listing.isTruncated() && (getMaxNumberOfMessagesToList()<0 || getMaxNumberOfMessagesToList() > result)) {
				listing = s3Client.listNextBatchOfObjects(listing);
				result += listing.getObjectSummaries().size();
			}
			return result;
		} catch (AmazonServiceException e) {
			throw new FileSystemException("Cannot process requested action", e);
		}
	}

	@Override
	public DirectoryStream<S3Object> listFiles(String folder) throws FileSystemException {
		List<S3ObjectSummary> summaries = null;
		String prefix = folder != null ? folder + "/" : "";
		try {
			ObjectListing listing = s3Client.listObjects(bucketName, prefix);
			summaries = listing.getObjectSummaries();
			while (listing.isTruncated()) {
				listing = s3Client.listNextBatchOfObjects(listing);
				summaries.addAll(listing.getObjectSummaries());
			}
		} catch (AmazonServiceException e) {
			throw new FileSystemException("Cannot process requested action", e);
		}

		List<S3Object> list = new ArrayList<S3Object>();
		for (S3ObjectSummary summary : summaries) {
			S3Object object = new S3Object();
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setContentLength(summary.getSize());

			object.setBucketName(summary.getBucketName());
			object.setKey(summary.getKey());
			object.setObjectMetadata(metadata);
			if(!object.getKey().endsWith("/") && !(prefix.isEmpty() && object.getKey().contains("/"))) {
				list.add(object);
			} 
		}

		return FileSystemUtils.getDirectoryStream(list.iterator());
	}

	@Override
	public boolean exists(S3Object f) throws FileSystemException {
		return s3Client.doesObjectExist(bucketName, f.getKey());
	}

	@Override
	public OutputStream createFile(final S3Object f) throws FileSystemException, IOException {
		String fileName = FileUtils.getTempDirectory().getAbsolutePath() + "tempFile";

		final File file = new File(fileName);
		final FileOutputStream fos = new FileOutputStream(file);
		final BufferedOutputStream bos = new BufferedOutputStream(fos);

		FilterOutputStream filterOutputStream = new FilterOutputStream(bos) {
			boolean isClosed = false;
			@Override
			public void close() throws IOException {
				super.close();
				bos.close();
				if(!isClosed) {
					try (FileInputStream fis = new FileInputStream(file)) {
						ObjectMetadata metaData = new ObjectMetadata();
						metaData.setContentLength(file.length());
	
						s3Client.putObject(bucketName, f.getKey(), fis, metaData);
					} finally {
						file.delete();
						isClosed = true;
					}
				}
			}
		};
		return filterOutputStream;
	}

	@Override
	public OutputStream appendFile(S3Object f) throws FileSystemException, IOException {
		// Amazon S3 doesn't support append operation
		return null;
	}

	@Override
	public Message readFile(S3Object f) throws FileSystemException, IOException {
		try {
			final S3Object file = s3Client.getObject(bucketName, f.getKey());
			final S3ObjectInputStream is = file.getObjectContent();

			return new Message(is);
		} catch (AmazonServiceException e) {
			throw new FileSystemException(e);
		}
	}

	@Override
	public void deleteFile(S3Object f) throws FileSystemException {
		try {
			DeleteObjectRequest deleteObjectRequest = new DeleteObjectRequest(bucketName, f.getKey());
			s3Client.deleteObject(deleteObjectRequest);
		} catch (AmazonServiceException e) {
			throw new FileSystemException(e);
		}
	}

	@Override
	public boolean folderExists(String folder) throws FileSystemException {
		ObjectListing objectListing = s3Client.listObjects(bucketName);
		Iterator<S3ObjectSummary> objIter = objectListing.getObjectSummaries().iterator();
		while (objIter.hasNext()) {
			S3ObjectSummary s3ObjectSummary = objIter.next();
			String key = s3ObjectSummary.getKey();
			if(key.endsWith("/") && key.equals(folder+"/")){
				return true;
			}
		}
		return false;
	}

	@Override
	public void createFolder(String folder) throws FileSystemException {
		String folderName = folder.endsWith("/") ? folder : folder + "/";
		if (!folderExists(folder)) {
			s3Client.putObject(bucketName, folderName, "");
		} else {
			throw new FileSystemException("Create directory for [" + folderName + "] has failed. Directory already exists.");
		}
	}

	@Override
	public void removeFolder(String folder) throws FileSystemException {
		if (folderExists(folder)) {
			folder = folder.endsWith("/") ? folder : folder + "/";
			s3Client.deleteObject(bucketName, folder);
		} else {
			throw new FileSystemException("Remove directory for [" + folder + "] has failed. Directory does not exist.");
		}
	}

	@Override
	public S3Object renameFile(S3Object source, S3Object destination) throws FileSystemException {
		s3Client.copyObject(bucketName, source.getKey(), bucketName, destination.getKey());
		s3Client.deleteObject(bucketName, source.getKey());
		return destination;
	}

	@Override
	public S3Object copyFile(S3Object f, String destinationFolder, boolean createFolder) throws FileSystemException {
		String destinationFile = destinationFolder+"/"+f.getKey();
		s3Client.copyObject(bucketName, f.getKey(), bucketName, destinationFile);
		return toFile(destinationFile);
	}

	@Override
	public S3Object moveFile(S3Object f, String destinationFolder, boolean createFolder) throws FileSystemException {
		return renameFile(f,toFile(destinationFolder,f.getKey()));
	}


	@Override
	public Map<String, Object> getAdditionalFileProperties(S3Object f) {
		Map<String, Object> attributes = new HashMap<String, Object>();
		attributes.put("name", bucketName);
		return attributes;
	}
	
	@Override
	public long getFileSize(S3Object f) throws FileSystemException {
		return f.getObjectMetadata().getContentLength();
	}

	@Override
	public String getName(S3Object f) {
		return f.getKey();
	}

	@Override
	public String getCanonicalName(S3Object f) throws FileSystemException {
		return f.getBucketName() + f.getKey();
	}

	@Override
	public Date getModificationTime(S3Object f) throws FileSystemException {
		S3Object file;
		if(f.getKey().isEmpty()) {
			return null;
		}
		file = s3Client.getObject(bucketName, f.getKey());
		Date date = file.getObjectMetadata().getLastModified();
		return date;
	}

	/**
	* Creates a bucket on Amazon S3.
	*
	* @param bucketName
	*            The desired name for a bucket that is about to be created. The class {@link BucketNameUtils} 
	*            provides a method that can check if the bucketName is valid. This is done just before the bucketName is used here.
	* @param bucketExistsThrowException
	* 			  This parameter is used for controlling the behavior for whether an exception has to be thrown or not. 
	* 			  In case of upload action being configured to be able to create a bucket, an exception will not be thrown when a bucket with assigned bucketName already exists.
	*/
	public String createBucket(String bucketName, boolean bucketExistsThrowException) throws SenderException {
		try {
			if (!s3Client.doesBucketExistV2(bucketName)) {
				CreateBucketRequest createBucketRequest = null;
				if (isForceGlobalBucketAccessEnabled())
					createBucketRequest = new CreateBucketRequest(bucketName, getBucketRegion());
				else
					createBucketRequest = new CreateBucketRequest(bucketName);
				s3Client.createBucket(createBucketRequest);
				log.debug("Bucket with bucketName: [" + bucketName + "] is created.");
			} else if (bucketExistsThrowException)
				throw new SenderException(" bucket with bucketName [" + bucketName + "] already exists, please specify a unique bucketName");

		} catch (AmazonServiceException e) {
			log.warn("Failed to create bucket with bucketName [" + bucketName + "].");
			throw new SenderException("Failed to create bucket with bucketName [" + bucketName + "]." + e);
		}

		return bucketName;
	}

	/**
	 * Deletes a bucket on Amazon S3.
	 */
	public String deleteBucket() throws SenderException {
		try {
			bucketDoesNotExist(bucketName);
			DeleteBucketRequest deleteBucketRequest = new DeleteBucketRequest(bucketName);
			s3Client.deleteBucket(deleteBucketRequest);
			log.debug("Bucket with bucketName [" + bucketName + "] is deleted.");
		} catch (AmazonServiceException e) {
			log.warn("Failed to delete bucket with bucketName [" + bucketName + "].");
			throw new SenderException("Failed to delete bucket with bucketName [" + bucketName + "].");
		}

		return bucketName;
	}

	/**
	 * Copies a file from one Amazon S3 bucket to another one. 
	 *
	 * @param fileName
	 * 				This is the name of the file that is desired to be copied.
	 * 
	 * @param destinationFileName
	 * 				The name of the destination file
	 */
	public String copyObject(String fileName, String destinationFileName) throws SenderException {
		try {
			bucketDoesNotExist(bucketName); //if bucket does not exists this method throws an exception
			fileDoesNotExist(bucketName, fileName); //if object does not exists this method throws an exception
			if (!s3Client.doesBucketExistV2(destinationBucketName))
				bucketCreationWithObjectAction(destinationBucketName);
			if (!s3Client.doesObjectExist(destinationBucketName, destinationFileName)) {
				CopyObjectRequest copyObjectRequest = new CopyObjectRequest(bucketName, fileName, destinationBucketName, destinationFileName);
				if (isStorageClassEnabled())
					copyObjectRequest.setStorageClass(getStorageClass());
				s3Client.copyObject(copyObjectRequest);
				log.debug("Object with fileName [" + fileName + "] copied from bucket with bucketName [" + bucketName
						+ "] into bucket with bucketName [" + destinationBucketName + "] and new fileName ["
						+ destinationFileName + "]");
			} else
				throw new SenderException(" file with given name already exists, please specify a new name");
		} catch (AmazonServiceException e) {
			log.error("Failed to perform [copy] action on object with fileName [" + fileName + "]");
			throw new SenderException("Failed to perform [copy] action on object with fileName [" + fileName + "]");
		}

		return destinationFileName;
	}

	public String restoreObject(String fileName) throws SenderException {
		Boolean restoreFlag;
		try {
			bucketDoesNotExist(bucketName);
			fileDoesNotExist(bucketName, fileName);
			RestoreObjectRequest requestRestore = new RestoreObjectRequest(bucketName, fileName, expirationInDays).withTier(tier);
			s3Client.restoreObjectV2(requestRestore);
			log.debug("Object with fileName [" + fileName + "] and bucketName [" + bucketName + "] restored from Amazon S3 Glacier");

			ObjectMetadata response = s3Client.getObjectMetadata(bucketName, fileName);
			restoreFlag = response.getOngoingRestore();
			System.out.format("Restoration status: %s.\n", restoreFlag ? "in progress" : "not in progress (finished or failed)");

		} catch (AmazonServiceException e) {
			log.error("Failed to perform [restore] action, and restore object with fileName [" + fileName + "] from Amazon S3 Glacier");
			throw new SenderException("Failed to perform [restore] action, and restore object with fileName [" + fileName + "] from Amazon S3 Glacier");
		}

		String prefix = "Restoration status: %s.\n";
		return restoreFlag ? prefix + "in progress" : prefix + "not in progress (finished or failed)";
	}

	/**
	 * This method is wrapper which makes it possible for upload and copy actions to create a bucket and 
	 * in case a bucket already exists the operation will proceed without throwing an exception. 
	 *
	 * @param bucketName
	 *            The name of the bucket that is addressed. 
	 */
	public void bucketCreationWithObjectAction(String bucketName) throws SenderException {
		if (isBucketCreationEnabled())
			createBucket(bucketName, !bucketExistsThrowException);
		else
			throw new SenderException("Failed to create a bucket, to create a bucket bucketCreationEnabled attribute must be assinged to [true]");
	}

	/**
	 * This is a help method which throws an exception if a bucket does not exist.
	 *
	 * @param bucketName
	 *            The name of the bucket that is processed. 
	 */
	public void bucketDoesNotExist(String bucketName) throws SenderException {
		if (!s3Client.doesBucketExistV2(bucketName))
			throw new SenderException(" bucket with bucketName [" + bucketName + "] does not exist, please specify the name of an existing bucket");
	}

	/**
	 * This is a help method which throws an exception if a file does not exist.
	 *
	 * @param bucketName
	 *            The name of the bucket where the file is stored in.
	 * @param fileName
	 * 			  The name of the file that is processed. 
	 */
	public void fileDoesNotExist(String bucketName, String fileName) throws SenderException {
		if (!s3Client.doesObjectExist(bucketName, fileName))
			throw new SenderException(" file with fileName [" + fileName + "] does not exist, please specify the name of an existing file");
	}

	@Override
	public String getPhysicalDestinationName() {
		return "bucket ["+getBucketName()+"]"; 
	}

	public static List<String> getAvailableRegions() {
		List<String> availableRegions = new ArrayList<String>(Regions.values().length);
		for (Regions region : Regions.values())
			availableRegions.add(region.getName());

		return availableRegions;
	}

	public static List<String> getStorageClasses() {
		List<String> storageClasses = new ArrayList<String>(StorageClass.values().length);
		for (StorageClass storageClass : StorageClass.values())
			storageClasses.add(storageClass.toString());

		return storageClasses;
	}

	public static List<String> getTiers() {
		List<String> tiers = new ArrayList<String>(Tier.values().length);
		for (Tier tier : Tier.values())
			tiers.add(tier.toString());

		return tiers;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getAuthAlias() {
		return authAlias;
	}

	public void setAuthAlias(String authAlias) {
		this.authAlias = authAlias;
	}

	public AmazonS3 getS3Client() {
		return s3Client;
	}

	public void setS3Client(AmazonS3 s3Client) {
		this.s3Client = s3Client;
	}

	public boolean isChunkedEncodingDisabled() {
		return chunkedEncodingDisabled;
	}

	public void setChunkedEncodingDisabled(boolean chunkedEncodingDisabled) {
		this.chunkedEncodingDisabled = chunkedEncodingDisabled;
	}

	public boolean isForceGlobalBucketAccessEnabled() {
		return forceGlobalBucketAccessEnabled;
	}

	public void setForceGlobalBucketAccessEnabled(boolean forceGlobalBucketAccessEnabled) {
		this.forceGlobalBucketAccessEnabled = forceGlobalBucketAccessEnabled;
	}

	public String getClientRegion() {
		return clientRegion;
	}

	public void setClientRegion(String clientRegion) {
		this.clientRegion = clientRegion;
	}

	public String getBucketName() {
		return bucketName;
	}

	public void setBucketName(String bucketName) {
		this.bucketName = bucketName;
	}

	public String getDestinationBucketName() {
		return destinationBucketName;
	}

	public void setDestinationBucketName(String destinationBucketName) {
		this.destinationBucketName = destinationBucketName;
	}

	public String getBucketRegion() {
		return bucketRegion;
	}

	public void setBucketRegion(String bucketRegion) {
		this.bucketRegion = bucketRegion;
	}

	public String getStorageClass() {
		return storageClass;
	}

	public void setStorageClass(String storageClass) {
		this.storageClass = storageClass;
	}

	public String getTier() {
		return tier;
	}

	public void setTier(String tier) {
		this.tier = tier;
	}

	public int getExpirationInDays() {
		return expirationInDays;
	}

	public void setExpirationInDays(int experationInDays) {
		this.expirationInDays = experationInDays;
	}

	public boolean isStorageClassEnabled() {
		return storageClassEnabled;
	}

	public void setStorageClassEnabled(boolean storageClassEnabled) {
		this.storageClassEnabled = storageClassEnabled;
	}

	public boolean isBucketCreationEnabled() {
		return bucketCreationEnabled;
	}

	public void setBucketCreationEnabled(boolean bucketCreationEnabled) {
		this.bucketCreationEnabled = bucketCreationEnabled;
	}

	public boolean isBucketExistsThrowException() {
		return bucketExistsThrowException;
	}

	public ClientConfiguration getProxyConfig() {
		ClientConfiguration proxyConfig = null;
		if (this.getProxyHost() != null && this.getProxyPort() != null) {
			proxyConfig = new ClientConfiguration();
			proxyConfig.setProtocol(Protocol.HTTPS);
			proxyConfig.setProxyHost(this.getProxyHost());
			proxyConfig.setProxyPort(this.getProxyPort());
		}
		return proxyConfig;
	}

	public String getProxyHost() {
		return proxyHost;
	}

	public void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	public Integer getProxyPort() {
		return proxyPort;
	}

	public void setProxyPort(Integer proxyPort) {
		this.proxyPort = proxyPort;
	}

}
