using SendGrid;
using SendGrid.Helpers.Mail;
using Twilio.Rest.Api.V2010.Account;
using Twilio.Types;

namespace Shop.Web.Services;

public class OrderNotifier
{
    private readonly ISendGridClient _mail;
    private readonly string _from;

    public OrderNotifier(ISendGridClient mail, IConfiguration config)
    {
        _mail = mail;
        _from = config["Twilio:From"]!;
    }

    public async Task OrderShipped(string phone, string email, string tracking)
    {
        await MessageResource.CreateAsync(
            body: $"Your order shipped: {tracking}",
            from: new PhoneNumber(_from),
            to: new PhoneNumber(phone));

        var message = MailHelper.CreateSingleEmail(
            new EmailAddress("orders@example.com", "Shop"),
            new EmailAddress(email),
            "Your order shipped",
            $"Tracking number: {tracking}",
            null);
        await _mail.SendEmailAsync(message);
    }
}
