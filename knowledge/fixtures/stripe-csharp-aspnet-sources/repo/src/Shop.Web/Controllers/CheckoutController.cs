using Microsoft.AspNetCore.Mvc;
using Stripe;

namespace Shop.Web.Controllers;

[ApiController]
[Route("api/checkout")]
public class CheckoutController : ControllerBase
{
    private readonly StripeClient _stripe;

    public CheckoutController(StripeClient stripe)
    {
        _stripe = stripe;
    }

    // Bancontact through the legacy Sources API.
    [HttpPost("bancontact")]
    public async Task<IActionResult> Bancontact([FromBody] BancontactRequest request)
    {
        var options = new SourceCreateOptions
        {
            Type = "bancontact",
            Amount = request.Amount,
            Currency = "eur",
            Redirect = new SourceRedirectOptions { ReturnUrl = request.ReturnUrl },
        };
        var service = new SourceService();
        Source source = await service.CreateAsync(options);
        return Ok(new { source.Id, source.Redirect.Url });
    }

    [HttpPost("ach")]
    public async Task<IActionResult> Ach([FromBody] AchRequest request)
    {
        var source = await _stripe.V1.Sources.CreateAsync(new SourceCreateOptions
        {
            Type = "ach_credit_transfer",
            Currency = "usd",
            Owner = new SourceOwnerOptions { Email = request.Email },
        });
        return Ok(new { source.Id });
    }
}

public record BancontactRequest(long Amount, string ReturnUrl);
public record AchRequest(string Email);
