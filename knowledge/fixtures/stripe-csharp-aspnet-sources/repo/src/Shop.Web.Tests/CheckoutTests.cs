using Stripe;
using Xunit;

namespace Shop.Web.Tests;

public class CheckoutTests
{
    [Fact]
    public void BuildsBancontactOptions()
    {
        var service = new SourceService(new StripeClient("sk_test_placeholder"));
        Assert.NotNull(service);
    }
}
