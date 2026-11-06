package com.bw.pdfixit.template;

/**
 * The fields a layout can place. The id of a field is its name in {@code InvoiceData}; a field of a party is
 * {@code seller.<name>} or {@code buyer.<name>} with the name from {@code Party}. The two address blocks and the page
 * number are the only additions.
 */
public enum LayoutField {
    NUMBER("number"),
    ISSUE_DATE("issueDate"),
    DELIVERY_DATE("deliveryDate"),
    DUE_DATE("dueDate"),
    PERIOD_START("periodStart"),
    PERIOD_END("periodEnd"),
    BUYER_REFERENCE("buyerReference"),

    SELLER_NAME("seller.name"),
    SELLER_STREET("seller.street"),
    SELLER_ZIP("seller.zip"),
    SELLER_CITY("seller.city"),
    SELLER_COUNTRY("seller.country"),
    SELLER_VAT_ID("seller.vatId"),
    SELLER_TAX_ID("seller.taxId"),
    SELLER_EMAIL("seller.email"),
    SELLER_CONTACT_NAME("seller.contactName"),
    SELLER_PHONE("seller.phone"),
    SELLER_ACCOUNT_HOLDER("seller.accountHolder"),
    SELLER_IBAN("seller.iban"),
    SELLER_BIC("seller.bic"),

    BUYER_NAME("buyer.name"),
    BUYER_STREET("buyer.street"),
    BUYER_ZIP("buyer.zip"),
    BUYER_CITY("buyer.city"),
    BUYER_COUNTRY("buyer.country"),
    BUYER_VAT_ID("buyer.vatId"),
    BUYER_TAX_ID("buyer.taxId"),
    BUYER_EMAIL("buyer.email"),

    /**
     * The seller as an address: name, street, zip and city, country. With {@link Element#singleLine} all on one line,
     * as return address above the recipient.
     */
    SELLER_ADDRESS("seller.address", Kind.BLOCK, Placement.ANY, false),
    BUYER_ADDRESS("buyer.address", Kind.BLOCK, Placement.ANY, false),

    NOTE("note", Kind.BLOCK, Placement.ANY, true),
    PAYMENT_TERMS("paymentTerms", Kind.BLOCK, Placement.ANY, true),
    VAT_EXEMPTION_REASON("vatExemptionReason", Kind.BLOCK, Placement.ANY, true),

    /**
     * The items. Its "label" is the header row of the table.
     */
    LINES("lines", Kind.TABLE, Placement.FLOWING_ONLY, true),
    /**
     * Net, VAT per rate, gross, amount due. Its "label" are the captions.
     */
    TOTALS("totals", Kind.TABLE, Placement.FLOWING_ONLY, true),

    /**
     * Page number and count, "Seite 1 von 2".
     */
    PAGE("page", Kind.LINE, Placement.FIXED_ONLY, false);

    /**
     * How a value is printed.
     */
    public enum Kind {
        /**
         * One line that must fit the width of its region.
         */
        LINE,
        /**
         * Wraps with the font metrics and must fit the height.
         */
        BLOCK,
        /**
         * A table drawn by the engine.
         */
        TABLE
    }

    /**
     * Where a field may be placed.
     */
    public enum Placement {
        ANY,
        /**
         * Only with a rectangle on a page.
         */
        FIXED_ONLY,
        /**
         * Only in the body flow.
         */
        FLOWING_ONLY
    }

    public final String id;
    public final Kind kind;
    public final Placement placement;
    /**
     * True if the label is shown when the layout says nothing about it.
     */
    public final boolean labelByDefault;

    LayoutField(String id) {
        this(id, Kind.LINE, Placement.ANY, true);
    }

    LayoutField(String id, Kind kind, Placement placement, boolean labelByDefault) {
        this.id = id;
        this.kind = kind;
        this.placement = placement;
        this.labelByDefault = labelByDefault;
    }

    /**
     * The field of an id.
     *
     * @return The field, null if the id is unknown.
     */
    public static LayoutField of(String id) {
        for (LayoutField f : values())
            if (f.id.equals(id))
                return f;
        return null;
    }
}
