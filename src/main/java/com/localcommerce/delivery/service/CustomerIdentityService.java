package com.localcommerce.delivery.service;

import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class CustomerIdentityService {

    private final CustomerRepository customerRepository;

    public CustomerIdentityService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public Customer resolveCustomerIdentity(String bsuid, String phoneNumber, String name) {

        Optional<Customer> existingCustomer = customerRepository.findByBsuid(bsuid);
        if (existingCustomer.isPresent()) {

            // Optional: If we found them by BSUID, but we just got a new phone number for them, update it!
            Customer customer = existingCustomer.get();
            if (phoneNumber != null && !phoneNumber.isBlank() && customer.getWhatsappNumber() == null) {
                customer.setWhatsappNumber(phoneNumber);
                customerRepository.save(customer);
            }
            return customer;
        }

        // 2. If no BSUID match, check by Phone Number (Legacy fallback)
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            Optional<Customer> legacyCustomer = customerRepository.findByWhatsappNumber(phoneNumber);
            if (legacyCustomer.isPresent()) {
                Customer customer = legacyCustomer.get();
                // Attach the new BSUID to this legacy account
                customer.setBsuid(bsuid);
                return customerRepository.save(customer);
            }
        }

        // 3. If neither exist, it's a brand new customer!
        return createNewCustomer(bsuid, phoneNumber, name);
    }

    private Customer createNewCustomer(String bsuid, String whatsappNumber, String name) {
        Customer newCustomer = new Customer();
        newCustomer.setBsuid(bsuid);
        newCustomer.setWhatsappNumber(whatsappNumber);
        newCustomer.setName(name);
        return customerRepository.save(newCustomer);
    }
}
