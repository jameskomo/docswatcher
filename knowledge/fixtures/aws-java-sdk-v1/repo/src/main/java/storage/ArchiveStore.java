package storage;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.io.File;

public class ArchiveStore {

  private final AmazonS3 s3 = AmazonS3ClientBuilder.standard().build();

  public void archive(String bucket, String key, File payload) {
    s3.putObject(new PutObjectRequest(bucket, key, payload));
  }
}
