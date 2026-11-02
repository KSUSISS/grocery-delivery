package ru.komissarov.grocerydelivery.delivery;

public final class Types {
    private Types() {}
    public enum RoleCode { ADMIN, MANAGER, CLIENT, COURIER }
    public enum OrderStatus { NEW, ASSIGNED, IN_DELIVERY, COMPLETED }
    public enum AddressType { APARTMENT, PRIVATE_HOUSE }
    public enum EventType { CREATED, ASSIGNED, REASSIGNED, DISPATCHED, COMPLETED }
    public enum ReleaseReason { REASSIGNED, COMPLETED }
}
