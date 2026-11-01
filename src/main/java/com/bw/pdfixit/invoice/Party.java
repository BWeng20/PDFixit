package com.bw.pdfixit.invoice;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Seller or buyer of an invoice. Text fields are null or blank if not set.
 */
public class Party {
    public String name;
    public String street;
    public String zip;
    public String city;
    public String country = "DE";
    public String vatId;
    public String taxId;
    public String email;
    /**
     * Contact person and phone, only used for the seller. XRechnung requires them.
     */
    public String contactName;
    public String phone;
    /**
     * Bank account, only used for the seller (the account the buyer pays to).
     */
    public String iban;
    public String bic;
    public String accountHolder;

    /**
     * Only for presets of a buyer: default payment terms and days until due. A new invoice takes them over
     * if its own values are empty, see {@link InvoiceData#applyBuyerPaymentDefaults}. Not part of the invoice.
     */
    public String paymentTerms;
    public Integer paymentDays;

    /**
     * Only for presets: in which preset lists of the invoice editor the party is offered.
     */
    public boolean asSeller = true;
    public boolean asBuyer = true;

    /**
     * Only for presets: tells apart several versions of the same party, e.g. "Berlin branch".
     * Not part of the invoice.
     */
    public String label;

    /**
     * The values compared by {@link #findBestMatch}, besides the name.
     */
    private static final List<Function<Party, String>> MATCH_FIELDS = List.of(
            p -> p.street, p -> p.zip, p -> p.city, p -> p.country, p -> p.vatId, p -> p.taxId, p -> p.email,
            p -> p.contactName, p -> p.phone, p -> p.iban, p -> p.bic, p -> p.accountHolder);

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Same name, ignoring case and surrounding blanks. Blank names are never the same.
     */
    public static boolean sameName(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Same name and same label (ignoring case): the preset is the same entry.
     */
    public boolean sameIdentity(Party o) {
        return o != null && sameName(name, o.name) && norm(label).equals(norm(o.label));
    }

    /**
     * Name with the label, e.g. "ACME - Berlin branch". Empty if there is no name.
     */
    public String displayName() {
        String n = name == null || name.isBlank() ? "" : name.trim();
        return label == null || label.isBlank() ? n : n + " - " + label.trim();
    }

    /**
     * Finds the preset that fits the party best: the one with the same name and the most equal values.
     * Empty values never count as equal.
     * Ties are decided by fewer differing values, then by presets offered for the role, then by the order.
     *
     * @param role Preferred role, may be null.
     * @return The preset, null if no preset has the name of the probe.
     */
    public static Party findBestMatch(List<Party> presets, Party probe, Role role) {
        if (probe == null)
            return null;
        Party best = null;
        int bestEqual = -1;
        int bestDiff = 0;
        boolean bestRole = false;
        for (Party p : presets) {
            if (!sameName(p.name, probe.name))
                continue;
            int equal = 0;
            int diff = 0;
            for (Function<Party, String> f : MATCH_FIELDS) {
                String a = norm(f.apply(p)).replace(" ", "");
                String b = norm(f.apply(probe)).replace(" ", "");
                if (a.isEmpty() || b.isEmpty())
                    continue;
                if (a.equals(b))
                    equal++;
                else
                    diff++;
            }
            boolean roleOk = role != null && p.hasRole(role);
            boolean better = best == null || equal > bestEqual
                    || (equal == bestEqual && (diff < bestDiff || (diff == bestDiff && roleOk && !bestRole)));
            if (better) {
                best = p;
                bestEqual = equal;
                bestDiff = diff;
                bestRole = roleOk;
            }
        }
        return best;
    }

    /**
     * Checks if the preset is offered for this role.
     */
    public boolean hasRole(Role role) {
        return role == Role.SELLER ? asSeller : asBuyer;
    }

    public void setRole(Role role, boolean enabled) {
        if (role == Role.SELLER)
            asSeller = enabled;
        else
            asBuyer = enabled;
    }

    /**
     * Copies all values of the other party into this one.
     */
    public void copyFrom(Party o) {
        asSeller = o.asSeller;
        asBuyer = o.asBuyer;
        label = o.label;
        name = o.name;
        street = o.street;
        zip = o.zip;
        city = o.city;
        country = o.country;
        vatId = o.vatId;
        taxId = o.taxId;
        email = o.email;
        contactName = o.contactName;
        phone = o.phone;
        iban = o.iban;
        bic = o.bic;
        accountHolder = o.accountHolder;
        paymentTerms = o.paymentTerms;
        paymentDays = o.paymentDays;
    }

    /**
     * Checks if all values are the same as in the other party. Null and blank are different.
     */
    public boolean sameData(Party o) {
        return o != null && asSeller == o.asSeller && asBuyer == o.asBuyer
                && Objects.equals(label, o.label) && Objects.equals(name, o.name)
                && Objects.equals(paymentTerms, o.paymentTerms) && Objects.equals(paymentDays, o.paymentDays)
                && MATCH_FIELDS.stream().allMatch(f -> Objects.equals(f.apply(this), f.apply(o)));
    }

    public Party copy() {
        Party p = new Party();
        p.copyFrom(this);
        return p;
    }
}
