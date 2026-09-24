require "twilio-ruby"

module Community
  # Push notifications and the member chat, both built on Twilio in 2019.
  class Messaging
    def initialize
      @client = Twilio::REST::Client.new(ENV.fetch("TWILIO_ACCOUNT_SID"), ENV.fetch("TWILIO_AUTH_TOKEN"))
      @notify_sid = ENV.fetch("TWILIO_NOTIFY_SERVICE_SID")
      @chat_sid = ENV.fetch("TWILIO_CHAT_SERVICE_SID")
    end

    def register_device(identity, token)
      @client.notify.v1.services(@notify_sid).bindings.create(
        identity: identity,
        binding_type: "fcm",
        address: token
      )
    end

    def create_room(name)
      @client.chat.v2.services(@chat_sid).channels.create(friendly_name: name)
    end

    def chat_services
      @client.chat.v2.services.list(limit: 20)
    end

    def text_member(phone, body)
      @client.messages.create(from: ENV.fetch("TWILIO_FROM"), to: phone, body: body)
    end
  end
end
