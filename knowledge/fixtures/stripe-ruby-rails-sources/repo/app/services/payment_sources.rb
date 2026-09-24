# Creates reusable payment sources for customers who pay by bank debit.
class PaymentSources
  def initialize(customer)
    @customer = customer
  end

  # Legacy SEPA flow, still used by accounts created before 2023.
  def create_sepa_debit(iban:)
    Stripe::Source.create(
      type: "sepa_debit",
      sepa_debit: { iban: iban },
      currency: "eur",
      owner: { name: @customer.name, email: @customer.email }
    )
  end

  def create_ach_credit_transfer
    client = Stripe::StripeClient.new(Rails.application.credentials.stripe_secret_key)
    client.v1.sources.create({ type: "ach_credit_transfer", currency: "usd", owner: { email: @customer.email } })
  end

  # Attaches a card token to the customer. This is the customer sources
  # endpoint, not the Sources API, and must not be reported as one.
  def attach_card(token)
    stripe_customer = Stripe::Customer.retrieve(@customer.stripe_id)
    stripe_customer.sources.create(source: token)
  end
end
