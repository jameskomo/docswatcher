<?php

namespace Shop\Notifications;

use SendGrid\Mail\Mail;
use Twilio\Rest\Client;

class CustomerMessages
{
    public function __construct(
        private \SendGrid $sendgrid,
        private Client $twilio,
        private string $chatServiceSid,
    ) {
    }

    public function receipt(string $to, string $html): void
    {
        $email = new Mail();
        $email->setFrom('orders@example.com', 'Shop');
        $email->setSubject('Your receipt');
        $email->addTo($to);
        $email->addContent('text/html', $html);
        $this->sendgrid->send($email);
    }

    public function shippingText(string $phone, string $tracking): void
    {
        $this->twilio->messages->create($phone, [
            'from' => getenv('TWILIO_FROM'),
            'body' => "Your order shipped: {$tracking}",
        ]);
    }

    public function openSupportChannel(string $orderId)
    {
        return $this->twilio->chat->v2->services($this->chatServiceSid)
            ->channels
            ->create(['friendlyName' => "Order {$orderId}"]);
    }

    public function supportServices(): array
    {
        return $this->twilio->chat->v2->services->read(20);
    }
}
