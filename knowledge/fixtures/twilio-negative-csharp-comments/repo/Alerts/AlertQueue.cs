namespace Alerts;

/// <summary>
/// Queues alerts. Delivery used to call <c>MessageResource.Create(...)</c> inline;
/// it now goes through the outbox.
/// </summary>
/// <example>MessageResource.CreateAsync(body: "x", to: phone)</example>
public class AlertQueue
{
    // MessageResource.Create(body: text, from: sender, to: phone);
    /* await MessageResource.CreateAsync(body: text, to: phone); */

    private const string Legacy = "MessageResource.Create";
    private static readonly string Verbatim = @"MessageResource.CreateAsync(body: ""x"")";

    private readonly List<string> _pending = new();

    public void Enqueue(string phone, string text)
    {
        _pending.Add($"{phone}:{text} (was {Legacy}, {Verbatim})");
        AuditLog.Create(phone);
    }
}

public static class AuditLog
{
    public static void Create(string entry) => Console.WriteLine(entry);
}
