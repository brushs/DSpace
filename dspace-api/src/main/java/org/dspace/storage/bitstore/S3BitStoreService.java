/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Bitstream;
import org.dspace.core.Utils;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asset store using Amazon's Simple Storage Service (S3).
 * S3 is a commercial, web-service accessible, remote storage facility.
 * NB: you must have obtained an account with Amazon to use this store
 *
 * @author Richard Rodgers, Peter Dietz
 */

public class S3BitStoreService implements BitStoreService {
    /**
     * log4j log
     */
    private static final Logger log = LogManager.getLogger(S3BitStoreService.class);

    /**
     * Checksum algorithm
     */
    private static final String CSA = "MD5";

    private String awsAccessKey;
    private String awsSecretKey;
    private String awsRegionName;

    /**
     * container for all the assets
     */
    private String bucketName = null;

    /**
     * (Optional) subfolder within bucket where objects are stored
     */
    private String subfolder = null;

    /**
     * S3 service
     */
    private AmazonS3 s3Service = null;

    private static final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();
    public S3BitStoreService() {
    }

    /**
     * Initialize the asset store
     * S3 Requires:
     * - access key
     * - secret key
     * - bucket name
     */
    @Override
    public void init() throws IOException {
        if (StringUtils.isBlank(getAwsAccessKey()) || StringUtils.isBlank(getAwsSecretKey())) {
            log.warn("Empty S3 access or secret");
        }

        // init client
        AWSCredentials awsCredentials = new BasicAWSCredentials(getAwsAccessKey(), getAwsSecretKey());
        s3Service = new AmazonS3Client(awsCredentials);

        // bucket name
        if (StringUtils.isEmpty(bucketName)) {
            // get hostname of DSpace UI to use to name bucket
            String hostname = Utils.getHostName(configurationService.getProperty("dspace.ui.url"));
            bucketName = "dspace-asset-" + hostname;
            log.warn("S3 BucketName is not configured, setting default: " + bucketName);
        }

        try {
            if (!s3Service.doesBucketExist(bucketName)) {
                s3Service.createBucket(bucketName);
                log.info("Creating new S3 Bucket: " + bucketName);
            }
        } catch (AmazonClientException e) {
            log.error(e);
            throw new IOException(e);
        }

        // region
        if (StringUtils.isNotBlank(awsRegionName)) {
            try {
                Regions regions = Regions.fromName(awsRegionName);
                Region region = Region.getRegion(regions);
                s3Service.setRegion(region);
                log.info("S3 Region set to: " + region.getName());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid aws_region: " + awsRegionName);
            }
        }

        log.info("AWS S3 Assetstore ready to go! bucket:" + bucketName);
    }


    /**
     * Return an identifier unique to this asset store instance
     *
     * @return a unique ID
     */
    @Override
    public String generateId() {
        return Utils.generateKey();
    }

    /**
     * Retrieve the bits for the asset with ID. If the asset does not
     * exist, returns null.
     *
     * @param bitstream The ID of the asset to retrieve
     * @return The stream of bits, or null
     * @throws java.io.IOException If a problem occurs while retrieving the bits
     */
    @Override
    public InputStream get(Bitstream bitstream) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        try {
            S3Object object = s3Service.getObject(new GetObjectRequest(bucketName, key));
            return (object != null) ? object.getObjectContent() : null;
        } catch (AmazonClientException e) {
            log.error("get(" + key + ")", e);
            throw new IOException(e);
        }
    }

    /**
     * Store a stream of bits.
     *
     * <p>
     * If this method returns successfully, the bits have been stored.
     * If an exception is thrown, the bits have not been stored.
     * </p>
     *
     * @param in The stream of bits to store
     * @throws java.io.IOException If a problem occurs while storing the bits
     */
    @Override
    public void put(Bitstream bitstream, InputStream in) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        //Copy istream to temp file, and send the file, with some metadata
        File scratchFile = File.createTempFile(bitstream.getInternalId(), "s3bs");
        try {
            FileUtils.copyInputStreamToFile(in, scratchFile);
            long contentLength = scratchFile.length();

            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, scratchFile);
            PutObjectResult putObjectResult = s3Service.putObject(putObjectRequest);

            bitstream.setSizeBytes(contentLength);
            bitstream.setChecksum(putObjectResult.getETag());
            bitstream.setChecksumAlgorithm(CSA);

            scratchFile.delete();

        } catch (AmazonClientException | IOException e) {
            log.error("put(" + bitstream.getInternalId() + ", is)", e);
            throw new IOException(e);
        } finally {
            if (scratchFile.exists()) {
                scratchFile.delete();
            }
        }
    }

    /**
     * Obtain technical metadata about an asset in the asset store.
     *
     * Checksum used is (ETag) hex encoded 128-bit MD5 digest of an object's content as calculated by Amazon S3
     * (Does not use getContentMD5, as that is 128-bit MD5 digest calculated on caller's side)
     *
     * @param bitstream The asset to describe
     * @param attrs     A Map whose keys consist of desired metadata fields
     * @return attrs
     * A Map with key/value pairs of desired metadata
     * If file not found, then return null
     * @throws java.io.IOException If a problem occurs while obtaining metadata
     */
    @Override
    public Map about(Bitstream bitstream, Map attrs) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        try {
            ObjectMetadata objectMetadata = s3Service.getObjectMetadata(bucketName, key);

            if (objectMetadata != null) {
                if (attrs.containsKey("size_bytes")) {
                    attrs.put("size_bytes", objectMetadata.getContentLength());
                }
                if (attrs.containsKey("checksum")) {
                    attrs.put("checksum", objectMetadata.getETag());
                    attrs.put("checksum_algorithm", CSA);
                }
                if (attrs.containsKey("modified")) {
                    attrs.put("modified", String.valueOf(objectMetadata.getLastModified().getTime()));
                }
                return attrs;
            }
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return null;
            }
        } catch (AmazonClientException e) {
            log.error("about(" + key + ", attrs)", e);
            throw new IOException(e);
        }
        return null;
    }

    /**
     * Remove an asset from the asset store. An irreversible operation.
     *
     * @param bitstream The asset to delete
     * @throws java.io.IOException If a problem occurs while removing the asset
     */
    @Override
    public void remove(Bitstream bitstream) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        try {
            s3Service.deleteObject(bucketName, key);
        } catch (AmazonClientException e) {
            log.error("remove(" + key + ")", e);
            throw new IOException(e);
        }
    }

    /**
     * Utility Method: Prefix the key with a subfolder, if this instance assets are stored within subfolder
     *
     * @param id DSpace bitstream internal ID
     * @return full key prefixed with a subfolder, if applicable
     */
    public String getFullKey(String id) {
        if (StringUtils.isNotEmpty(subfolder)) {
            return subfolder + "/" + id;
        } else {
            return id;
        }
    }

    public String getAwsAccessKey() {
        return awsAccessKey;
    }

    @Autowired(required = true)
    public void setAwsAccessKey(String awsAccessKey) {
        this.awsAccessKey = awsAccessKey;
    }

    public String getAwsSecretKey() {
        return awsSecretKey;
    }

    @Autowired(required = true)
    public void setAwsSecretKey(String awsSecretKey) {
        this.awsSecretKey = awsSecretKey;
    }

    public String getAwsRegionName() {
        return awsRegionName;
    }

    public void setAwsRegionName(String awsRegionName) {
        this.awsRegionName = awsRegionName;
    }

    @Autowired(required = true)
    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getSubfolder() {
        return subfolder;
    }

    public void setSubfolder(String subfolder) {
        this.subfolder = subfolder;
    }

    /**
     * Contains a command-line testing tool. Expects arguments:
     * -a accessKey -s secretKey -f assetFileName
     *
     * @param args the command line arguments given
     * @throws Exception generic exception
     */
    public static void main(String[] args) throws Exception {
        //TODO Perhaps refactor to be a unit test. Can't mock this without keys though.

        // parse command line
        Options options = new Options();
        Option option;

        option = Option.builder("a").desc("access key").hasArg().required().build();
        options.addOption(option);

        option = Option.builder("s").desc("secret key").hasArg().required().build();
        options.addOption(option);

        option = Option.builder("f").desc("asset file name").hasArg().required().build();
        options.addOption(option);

        DefaultParser parser = new DefaultParser();

        CommandLine command;
        try {
            command = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            new HelpFormatter().printHelp(
                    S3BitStoreService.class.getSimpleName() + "options", options);
            return;
        }

        String accessKey = command.getOptionValue("a");
        String secretKey = command.getOptionValue("s");
        String assetFile = command.getOptionValue("f");

        S3BitStoreService store = new S3BitStoreService();

        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);

        store.s3Service = new AmazonS3Client(awsCredentials);

        //Todo configurable region
        Region usEast1 = Region.getRegion(Regions.US_EAST_1);
        store.s3Service.setRegion(usEast1);

        // get hostname of DSpace UI to use to name bucket
        String hostname = Utils.getHostName(configurationService.getProperty("dspace.ui.url"));
        //Bucketname should be lowercase
        store.bucketName = "dspace-asset-" + hostname + ".s3test";
        store.s3Service.createBucket(store.bucketName);
/* Broken in DSpace 6 TODO Refactor
        // time everything, todo, swtich to caliper
        long start = System.currentTimeMillis();
        // Case 1: store a file
        String id = store.generateId();
        System.out.print("put() file " + assetFile + " under ID " + id + ": ");
        FileInputStream fis = new FileInputStream(assetFile);
        //TODO create bitstream for assetfile...
        Map attrs = store.put(fis, id);
        long now =  System.currentTimeMillis();
        System.out.println((now - start) + " msecs");
        start = now;
        // examine the metadata returned
        Iterator iter = attrs.keySet().iterator();
        System.out.println("Metadata after put():");
        while (iter.hasNext())
        {
            String key = (String)iter.next();
            System.out.println( key + ": " + (String)attrs.get(key) );
        }
        // Case 2: get metadata and compare
        System.out.print("about() file with ID " + id + ": ");
        Map attrs2 = store.about(id, attrs);
        now =  System.currentTimeMillis();
        System.out.println((now - start) + " msecs");
        start = now;
        iter = attrs2.keySet().iterator();
        System.out.println("Metadata after about():");
        while (iter.hasNext())
        {
            String key = (String)iter.next();
            System.out.println( key + ": " + (String)attrs.get(key) );
        }
        // Case 3: retrieve asset and compare bits
        System.out.print("get() file with ID " + id + ": ");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(assetFile+".echo");
        InputStream in = store.get(id);
        Utils.bufferedCopy(in, fos);
        fos.close();
        in.close();
        now =  System.currentTimeMillis();
        System.out.println((now - start) + " msecs");
        start = now;
        // Case 4: remove asset
        System.out.print("remove() file with ID: " + id + ": ");
        store.remove(id);
        now =  System.currentTimeMillis();
        System.out.println((now - start) + " msecs");
        System.out.flush();
        // should get nothing back now - will throw exception
        store.get(id);
*/
    }
}
