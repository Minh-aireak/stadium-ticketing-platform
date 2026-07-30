package com.aireak.payment;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Requires real PostgreSQL container running on localhost:5435")
@SpringBootTest
class PaymentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
