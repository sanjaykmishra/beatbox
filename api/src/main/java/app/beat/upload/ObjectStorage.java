package app.beat.upload;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Configuration
@ConditionalOnExpression("'${beat.r2.account-id:}'.length() > 0")
public class ObjectStorage {

  @Bean
  public S3Presigner r2Presigner(
      @Value("${beat.r2.account-id}") String accountId,
      @Value("${beat.r2.access-key-id}") String accessKey,
      @Value("${beat.r2.secret-access-key}") String secretKey) {
    URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    return S3Presigner.builder()
        .endpointOverride(endpoint)
        .region(Region.of("auto"))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Bean
  public S3Client r2Client(
      @Value("${beat.r2.account-id}") String accountId,
      @Value("${beat.r2.access-key-id}") String accessKey,
      @Value("${beat.r2.secret-access-key}") String secretKey) {
    URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    return S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.of("auto"))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  public record PresignResult(String url, String key, String public_url) {}

  public static PresignResult presignPut(
      S3Presigner presigner,
      String bucket,
      String publicBaseUrl,
      String key,
      String contentType,
      Duration expires) {
    PutObjectRequest put =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    PutObjectPresignRequest req =
        PutObjectPresignRequest.builder().signatureDuration(expires).putObjectRequest(put).build();
    String url = presigner.presignPutObject(req).url().toString();
    String publicUrl =
        publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
    return new PresignResult(url, key, publicUrl);
  }
}
