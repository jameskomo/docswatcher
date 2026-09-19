package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * GitHub's private key download is PKCS#1. Java only understands PKCS#8. Requiring an
 * openssl conversion before deployment is a step that is easy to skip and produces a
 * confusing failure at the first webhook, so both formats must work.
 *
 * <p>The keys below were generated for this test alone and protect nothing.
 */
class GitHubAppJwtTest {

  private static final String PKCS1 =
      ""
          + "-----BEGIN RSA PRIVATE KEY-----\n"
          + "MIIEogIBAAKCAQEA4t5b6AebMTP/na1/4EV9J3RYb9X1uBvyhLHyEn5GHZlj0M3x\n"
          + "eBPakvBdZ/OJxQrw2/YL0fdl7e5AGB45tK6HPbU49kCUpEzUP+gEE/4eIiJ0jFKt\n"
          + "Wmkn/Y73rR3AxjbqxgR+mrkvaw4H/9rlRc+QYX3NAc/aHiP1prRJ+qbuhfnv13sx\n"
          + "aB6yJbUmDeyK5SGXy7DtL6i50KAAjJla4sgiDOiPwSMWXqM0t/5BtaA8i8zswJEg\n"
          + "Rzij5hulxV+y/FEMKULN+RWFqxKJwOsx/sRTO1AF+QBwY1+pwZjvxWG4ogm5IaGc\n"
          + "YyLKg9n3d26c7XFT4+cRSisKTc0oP0lvPqEcZwIDAQABAoIBAEGLmcUPUYSlL9Bn\n"
          + "/J6YpQCdqiFhkTUIighRew1hwzMxCngc0AtvvQIgBPSQFblCoHUEgMTAdAgKgiUV\n"
          + "snljxToMXjEzVl6jvdza1TbLf/w/tSmHh4MsB3xH7oHrKzN5UVo1mThtkWgeLSx8\n"
          + "GyqQvfeUx4KjATMdE49+3jLXQOme0aQTtN02ZSjFh6dmewLRGhZsOw9Aa1I1a9cg\n"
          + "k2H6Ec6+ssbnTpKDjsIFfEymWtvzvO022WZp0QTCeboFfGK/Ihyth/Zkw2+pqbCT\n"
          + "BKmwwAcTzonyh4IckM5UbLjhvec9LkyiV0ZBQ4kDpb8fC8jcDejyCUX3grDfvsMO\n"
          + "DvUZpmECgYEA63Dp6YIvtbtmWHwPWQl69RjIVgGcN+0Q7NpIyPKc+tDVbG41bG9h\n"
          + "QYCoactx8ccwn6y/nbGFfz5IfaEEy9GDX1gziGdRLj6IRco5jakJx0xDjd+cNCCp\n"
          + "KLp9dQUPq6IDXO4L4WimrrNvPPZaboGX1Gi0eV4m+hVbN74U7i1IRqECgYEA9q3Q\n"
          + "k6/xiUtiZRlzU0ayQe1RlSbuQvGFpxLno5FinBKijeBBPyBvmQA8oWfnaWvO1bbS\n"
          + "dHkIqPNbJOPyw6H6UHEY8ZGfN5I4Rg5bvoqIi63iFNmbz6gsyZQ+GPYqYpjq/pwa\n"
          + "A6DiosiQ310Y7aBl/Y7X5XewsDbl2pdMuAaObgcCgYBnTyuEp/hxYOKezwkZA5zt\n"
          + "cUtu2dQHgkGb/IhVjIevUvVjV9SfWRwu1tqPWZMNCV9foTiPZHb0h4rdfUsSeEOS\n"
          + "EazDHLq1dQDsxriMXXEJ/3/hAA6VnQM8+N/V+juPD080dsvFw9rn65pTALJbrQfH\n"
          + "T45pdxPZRoe5JPIgNMRzIQKBgDMeT+PbCRWnfotu27w9IUGSOV7MQ4Gx5T17KG93\n"
          + "+FRZdEAsYbkAMsAlEWttScJJ6gHVsgUa89V3IMMjTbKGxYWX+lBNGg59CZZS8WYp\n"
          + "9SPk873YXnaI5kcbpkar9JqcD86VcLqhw3VyVLtE4p6Tp68Ew+60f/P0XGuRAP/9\n"
          + "s9RPAoGANzjXMGdg0+BveDLp2VIWytl9mBbCItVL0L8HtFHN5XSuVO09/w85vJCL\n"
          + "EXTKYMDKKvVEQMK8ZPD8cZkbXDokmVylqv4m7awXbcbBLNCsi3pg6ugSWp0wfOs3\n"
          + "xLq1WTwk98R2jOUasbIQPUNEsh65fDOqNG0l4W3gkxkVFfMtHHA=\n"
          + "-----END RSA PRIVATE KEY-----\n";

  private static final String PKCS8 =
      ""
          + "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDi3lvoB5sxM/+d\n"
          + "rX/gRX0ndFhv1fW4G/KEsfISfkYdmWPQzfF4E9qS8F1n84nFCvDb9gvR92Xt7kAY\n"
          + "Hjm0roc9tTj2QJSkTNQ/6AQT/h4iInSMUq1aaSf9jvetHcDGNurGBH6auS9rDgf/\n"
          + "2uVFz5Bhfc0Bz9oeI/WmtEn6pu6F+e/XezFoHrIltSYN7IrlIZfLsO0vqLnQoACM\n"
          + "mVriyCIM6I/BIxZeozS3/kG1oDyLzOzAkSBHOKPmG6XFX7L8UQwpQs35FYWrEonA\n"
          + "6zH+xFM7UAX5AHBjX6nBmO/FYbiiCbkhoZxjIsqD2fd3bpztcVPj5xFKKwpNzSg/\n"
          + "SW8+oRxnAgMBAAECggEAQYuZxQ9RhKUv0Gf8npilAJ2qIWGRNQiKCFF7DWHDMzEK\n"
          + "eBzQC2+9AiAE9JAVuUKgdQSAxMB0CAqCJRWyeWPFOgxeMTNWXqO93NrVNst//D+1\n"
          + "KYeHgywHfEfugesrM3lRWjWZOG2RaB4tLHwbKpC995THgqMBMx0Tj37eMtdA6Z7R\n"
          + "pBO03TZlKMWHp2Z7AtEaFmw7D0BrUjVr1yCTYfoRzr6yxudOkoOOwgV8TKZa2/O8\n"
          + "7TbZZmnRBMJ5ugV8Yr8iHK2H9mTDb6mpsJMEqbDABxPOifKHghyQzlRsuOG95z0u\n"
          + "TKJXRkFDiQOlvx8LyNwN6PIJRfeCsN++ww4O9RmmYQKBgQDrcOnpgi+1u2ZYfA9Z\n"
          + "CXr1GMhWAZw37RDs2kjI8pz60NVsbjVsb2FBgKhpy3HxxzCfrL+dsYV/Pkh9oQTL\n"
          + "0YNfWDOIZ1EuPohFyjmNqQnHTEON35w0IKkoun11BQ+rogNc7gvhaKaus2889lpu\n"
          + "gZfUaLR5Xib6FVs3vhTuLUhGoQKBgQD2rdCTr/GJS2JlGXNTRrJB7VGVJu5C8YWn\n"
          + "EuejkWKcEqKN4EE/IG+ZADyhZ+dpa87VttJ0eQio81sk4/LDofpQcRjxkZ83kjhG\n"
          + "Dlu+ioiLreIU2ZvPqCzJlD4Y9ipimOr+nBoDoOKiyJDfXRjtoGX9jtfld7CwNuXa\n"
          + "l0y4Bo5uBwKBgGdPK4Sn+HFg4p7PCRkDnO1xS27Z1AeCQZv8iFWMh69S9WNX1J9Z\n"
          + "HC7W2o9Zkw0JX1+hOI9kdvSHit19SxJ4Q5IRrMMcurV1AOzGuIxdcQn/f+EADpWd\n"
          + "Azz439X6O48PTzR2y8XD2ufrmlMAslutB8dPjml3E9lGh7kk8iA0xHMhAoGAMx5P\n"
          + "49sJFad+i27bvD0hQZI5XsxDgbHlPXsob3f4VFl0QCxhuQAywCURa21JwknqAdWy\n"
          + "BRrz1XcgwyNNsobFhZf6UE0aDn0JllLxZin1I+TzvdhedojmRxumRqv0mpwPzpVw\n"
          + "uqHDdXJUu0TinpOnrwTD7rR/8/Rca5EA//2z1E8CgYA3ONcwZ2DT4G94MunZUhbK\n"
          + "2X2YFsIi1UvQvwe0Uc3ldK5U7T3/Dzm8kIsRdMpgwMoq9URAwrxk8PxxmRtcOiSZ\n"
          + "XKWq/ibtrBdtxsEs0KyLemDq6BJanTB86zfEurVZPCT3xHaM5RqxshA9Q0SyHrl8\n"
          + "M6o0bSXhbeCTGRUV8y0ccA==\n"
          + "-----END PRIVATE KEY-----\n";

  @Test
  void accepts_the_key_file_github_downloads() {
    String jwt = new GitHubAppJwt("4998498", PKCS1).sign(Instant.now());
    assertThat(jwt.split("\\.")).hasSize(3);
  }

  @Test
  void accepts_a_pkcs8_conversion_of_the_same_key() {
    String jwt = new GitHubAppJwt("4998498", PKCS8).sign(Instant.now());
    assertThat(jwt.split("\\.")).hasSize(3);
  }

  @Test
  void both_formats_of_one_key_produce_the_same_signature() {
    Instant t = Instant.parse("2026-09-19T00:00:00Z");
    assertThat(new GitHubAppJwt("4998498", PKCS1).sign(t))
        .isEqualTo(new GitHubAppJwt("4998498", PKCS8).sign(t));
  }

  @Test
  void the_claims_are_what_github_requires() {
    Instant t = Instant.parse("2026-09-19T00:00:00Z");
    String payload = new String(
        java.util.Base64.getUrlDecoder().decode(new GitHubAppJwt("4998498", PKCS1).sign(t).split("\\.")[1]));
    assertThat(payload).contains("\"iss\":\"4998498\"");
    // Issued slightly in the past to tolerate clock skew, and under ten minutes long.
    assertThat(payload).contains("\"iat\":" + (t.getEpochSecond() - 60));
    assertThat(payload).contains("\"exp\":" + (t.getEpochSecond() + 540));
  }

  @Test
  void a_key_that_is_neither_format_fails_with_an_actionable_message() {
    assertThatThrownBy(() -> new GitHubAppJwt("1", "-----BEGIN PRIVATE KEY-----\nnonsense\n-----END PRIVATE KEY-----"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GITHUB_APP_PRIVATE_KEY");
  }
}
