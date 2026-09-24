require "sendgrid-ruby"

class ReceiptNotifier
  include SendGrid

  def initialize(order)
    @order = order
  end

  def deliver
    send_sms
    send_email
  end

  private

  def send_sms
    client = Twilio::REST::Client.new(ENV["TWILIO_ACCOUNT_SID"], ENV["TWILIO_AUTH_TOKEN"])
    client.messages.create(
      from: ENV["TWILIO_FROM"],
      to: @order.phone,
      body: "Order #{@order.number} is confirmed."
    )
  end

  def send_email
    mail = Mail.new(Email.new(email: "orders@example.com"), "Your receipt", Email.new(email: @order.email),
                    Content.new(type: "text/plain", value: "Thanks for your order."))
    sg = SendGrid::API.new(api_key: ENV["SENDGRID_API_KEY"])
    sg.client.mail._("send").post(request_body: mail.to_json)
  end
end
