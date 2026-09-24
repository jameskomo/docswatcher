<?php

namespace Shop\Checkout;

use Stripe\StripeClient;

class PaymentMethods
{
    private StripeClient $stripe;

    public function __construct(string $secretKey)
    {
        \Stripe\Stripe::setApiKey($secretKey);
        $this->stripe = new StripeClient($secretKey);
    }

    public function giropay(int $amount, string $returnUrl)
    {
        return \Stripe\Source::create([
            'type' => 'giropay',
            'amount' => $amount,
            'currency' => 'eur',
            'redirect' => ['return_url' => $returnUrl],
        ]);
    }

    public function sofort(int $amount, string $country)
    {
        return $this->stripe->sources->create([
            'type' => 'sofort',
            'amount' => $amount,
            'currency' => 'eur',
            'sofort' => ['country' => $country],
        ]);
    }

    public function refund(string $charge)
    {
        return $this->stripe->refunds->create(['charge' => $charge]);
    }
}
