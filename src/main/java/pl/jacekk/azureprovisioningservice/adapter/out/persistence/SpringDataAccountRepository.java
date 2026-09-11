package pl.jacekk.azureprovisioningservice.adapter.out.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

interface SpringDataAccountRepository extends MongoRepository<AccountDocument, String> {

    Optional<AccountDocument> findBySubscriptionName(String subscriptionName);
}
