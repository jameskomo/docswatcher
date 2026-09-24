require "rails_helper"

RSpec.describe PaymentSources do
  it "creates a SEPA source" do
    allow(Stripe::Source).to receive(:create).and_return(double(id: "src_123"))
    source = Stripe::Source.create(type: "sepa_debit", currency: "eur")
    expect(source.id).to eq("src_123")
  end
end
