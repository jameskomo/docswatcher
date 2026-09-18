package pay;

import com.stripe.model.Source;
import com.stripe.net.RequestOptions;
import java.util.Map;

public class SourceClient {

  private static final RequestOptions OPTIONS =
      RequestOptions.builder().setStripeVersionOverride("2020-08-27").build();

  public Source createCardSource(String token) throws Exception {
    Map<String, Object> params = Map.of("type", "card", "token", token);
    return Source.create(params, OPTIONS);
  }
}
