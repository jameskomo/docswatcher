package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

  static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  static final String TOKEN = "glpat-abcdefghijklmnopqrst";

  @Test
  void a_token_comes_back_out_and_is_not_in_what_is_stored() {
    TokenCipher cipher = new TokenCipher(KEY);
    byte[] sealed = cipher.encrypt(TOKEN, -1);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN).doesNotContain("glpat");
    assertThat(cipher.decrypt(sealed, -1)).isEqualTo(TOKEN);
  }

  @Test
  void the_same_token_seals_differently_each_time() {
    TokenCipher cipher = new TokenCipher(KEY);
    assertThat(cipher.encrypt(TOKEN, -1)).isNotEqualTo(cipher.encrypt(TOKEN, -1));
  }

  @Test
  void a_ciphertext_moved_to_another_connection_does_not_decrypt() {
    TokenCipher cipher = new TokenCipher(KEY);
    byte[] sealed = cipher.encrypt(TOKEN, -1);
    assertThatThrownBy(() -> cipher.decrypt(sealed, -2)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(TOKEN);
  }

  @Test
  void a_tampered_ciphertext_does_not_decrypt() {
    TokenCipher cipher = new TokenCipher(KEY);
    byte[] sealed = cipher.encrypt(TOKEN, -1);
    sealed[sealed.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(sealed, -1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void another_key_does_not_decrypt() {
    byte[] sealed = new TokenCipher(KEY).encrypt(TOKEN, -1);
    TokenCipher other = new TokenCipher("HwAeHRwbGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgE=");
    assertThatThrownBy(() -> other.decrypt(sealed, -1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void without_a_usable_key_nothing_is_encrypted_and_the_reason_is_given() {
    assertThat(new TokenCipher("").available()).isFalse();
    assertThat(new TokenCipher("c2hvcnQ=").problem()).contains("32 bytes");
    assertThat(new TokenCipher("not base64!").problem()).contains("not base64");
    assertThatThrownBy(() -> new TokenCipher((String) null).encrypt(TOKEN, -1)).isInstanceOf(TokenCipher.KeyUnavailable.class);
  }
}
