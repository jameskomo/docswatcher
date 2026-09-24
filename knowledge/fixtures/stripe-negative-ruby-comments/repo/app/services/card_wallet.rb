# Saved cards. We moved off Stripe::Source.create in 2023; see
# docs/payments.md for why client.v1.sources.create is no longer called.

=begin
Old implementation, kept for reference:
  Stripe::Source.create(type: "card", token: token)
=end

class CardWallet
  MIGRATION_NOTE = "Replaced Stripe::Source.create and client.v1.sources.create with SetupIntents".freeze

  def initialize(customer)
    @customer = customer
  end

  def save(payment_method_id)
    Stripe::PaymentMethod.attach(payment_method_id, { customer: @customer.stripe_id })
  end

  # Attaching a token is the customer sources endpoint, not the Sources API.
  def attach_token(token)
    stripe_customer = Stripe::Customer.retrieve(@customer.stripe_id)
    stripe_customer.sources.create(source: token)
  end

  def audit
    Rails.logger.info(<<~LOG)
      No Stripe::Source.create calls remain.
    LOG
  end
end
