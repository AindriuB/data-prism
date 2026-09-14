package io.github.aindriub.dataprism.quickstart.fixtures;

/**
 * One synthetic customer record, shaped the way this fixture source happens
 * to hold it — no privacy classification here, because that decision belongs
 * to the reviewed adapter that reads this response, not to the source.
 */
public record CustomerRecord(String customerId, String customerName, String email, String status) {
}
