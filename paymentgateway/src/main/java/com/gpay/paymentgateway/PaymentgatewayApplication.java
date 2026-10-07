package com.gpay.paymentgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync   // WebhookDispatcher: webhooks are sent after /gateway/topup has responded
public class PaymentgatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentgatewayApplication.class, args);
	}

}
