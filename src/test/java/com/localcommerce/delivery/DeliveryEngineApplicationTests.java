package com.localcommerce.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "SPRING_DATASOURCE_URL=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "SPRING_DATASOURCE_USERNAME=sa",
        "SPRING_DATASOURCE_PASSWORD=sa",
        "WHATSAPP_VERIFY_TOKEN=dummy",
        "WHATSAPP_API_URL=http://dummy",
        "WHATSAPP_API_TOKEN=dummy",
        "WHATSAPP_CATALOG_ID=dummy",
        "WHATSAPP_APP_SECRET=dummy",
        "WAREHOUSE_PHONE_NUMBER=123",
        "FALLBACK_MANAGER_PHONE=123",
        "AUTHORIZED_RIDER_NUMBERS=123",
        "ADMIN_API_KEY=dummy"
})
class DeliveryEngineApplicationTests {

	@Test
	void contextLoads() {
	}

}
