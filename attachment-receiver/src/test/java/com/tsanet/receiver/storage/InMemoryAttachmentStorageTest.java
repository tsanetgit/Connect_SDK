package com.tsanet.receiver.storage;

/** Binds the in-memory double to the contract every adapter must pass. */
class InMemoryAttachmentStorageTest extends AttachmentStorageContractTest {

    @Override
    protected AttachmentStorage newStorage() {
        return new InMemoryAttachmentStorage();
    }
}
