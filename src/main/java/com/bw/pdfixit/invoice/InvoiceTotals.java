package com.bw.pdfixit.invoice;

import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.bw.pdfixit.invoice.CiiXml.children;
import static com.bw.pdfixit.invoice.CiiXml.ensure;
import static com.bw.pdfixit.invoice.CiiXml.find;
import static com.bw.pdfixit.invoice.CiiXml.insert;
import static com.bw.pdfixit.invoice.CiiXml.set;
import static com.bw.pdfixit.invoice.CiiXml.text;

/**
 * Calculates the sums of a CII invoice from its items, as EN 16931 defines them, and writes them into the XML:
 * the line totals, the VAT breakdown and the document totals.<br>
 * Allowances and charges (on items and on the document), prepaid and rounding amounts are taken into account.
 */
final class InvoiceTotals {

    /**
     * VAT categories that need an exemption reason (EN 16931 BR-E-10, BR-AE-10, BR-IC-10, BR-G-10, BR-O-10).
     */
    static final Set<String> EXEMPT_CATEGORIES = Set.of("E", "AE", "K", "G", "O");

    private InvoiceTotals() {
    }

    private record TaxKey(String category, BigDecimal rate) {
        TaxKey {
            // Category "O" (outside of scope) has no rate: one with and one without rate are the same entry.
            rate = rate == null || "O".equals(category) ? null : rate.stripTrailingZeros();
        }
    }

    /**
     * Calculates and writes the sums. An invoice without items (e.g. profile MINIMUM) is not changed.
     *
     * @param root            The invoice.
     * @param exemptionReason Reason for VAT categories that need one, if the XML has none. May be null.
     * @return The sums.
     */
    static InvoiceData.Totals update(Element root, String exemptionReason) {
        Element tx = find(root, "SupplyChainTradeTransaction");
        Element settlement = ensure(tx, "ApplicableHeaderTradeSettlement");
        List<Element> lines = children(tx, "IncludedSupplyChainTradeLineItem");
        if (lines.isEmpty())
            return read(settlement);

        Map<TaxKey, BigDecimal> basis = new LinkedHashMap<>();
        BigDecimal lineSum = BigDecimal.ZERO;
        for (Element line : lines) {
            Element ls = ensure(line, "SpecifiedLineTradeSettlement");
            BigDecimal total = lineTotal(line, ls);
            setAmount(ensure(ls, "SpecifiedTradeSettlementLineMonetarySummation"), total, "LineTotalAmount");
            lineSum = lineSum.add(total);
            Element tax = find(ls, "ApplicableTradeTax");
            basis.merge(new TaxKey(text(tax, "CategoryCode"), decimal(text(tax, "RateApplicablePercent"))), total, BigDecimal::add);
        }

        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal allowances = BigDecimal.ZERO;
        for (Element ac : children(settlement, "SpecifiedTradeAllowanceCharge")) {
            BigDecimal amount = round(orZero(decimal(text(ac, "ActualAmount"))));
            boolean charge = isCharge(ac);
            if (charge)
                charges = charges.add(amount);
            else
                allowances = allowances.add(amount);
            // Without a VAT category the amount belongs to no entry of the breakdown.
            Element tax = find(ac, "CategoryTradeTax");
            if (tax != null)
                basis.merge(new TaxKey(text(tax, "CategoryCode"), decimal(text(tax, "RateApplicablePercent"))),
                        charge ? amount : amount.negate(), BigDecimal::add);
        }

        // VAT breakdown: one entry per category and rate. Existing entries keep their other data (e.g. exemption reason).
        Map<TaxKey, Element> oldTax = new LinkedHashMap<>();
        for (Element t : children(settlement, "ApplicableTradeTax")) {
            oldTax.putIfAbsent(new TaxKey(text(t, "CategoryCode"), decimal(text(t, "RateApplicablePercent"))), t);
            settlement.removeChild(t);
        }
        BigDecimal taxSum = BigDecimal.ZERO;
        for (var e : basis.entrySet()) {
            TaxKey key = e.getKey();
            BigDecimal base = round(e.getValue());
            BigDecimal rate = orZero(key.rate());
            BigDecimal amount = round(base.multiply(rate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            taxSum = taxSum.add(amount);

            writeTax(root, settlement, oldTax.get(key), key, base, amount, exemptionReason);
        }

        BigDecimal taxBasis = lineSum.add(charges).subtract(allowances);
        BigDecimal grand = taxBasis.add(taxSum);
        Element sum = ensure(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        BigDecimal prepaid = orZero(decimal(text(sum, "TotalPrepaidAmount")));
        BigDecimal rounding = orZero(decimal(text(sum, "RoundingAmount")));
        BigDecimal due = grand.subtract(prepaid).add(rounding);

        setAmount(sum, lineSum, "LineTotalAmount");
        setOptionalAmount(sum, charges, "ChargeTotalAmount");
        setOptionalAmount(sum, allowances, "AllowanceTotalAmount");
        setAmount(sum, taxBasis, "TaxBasisTotalAmount");
        setTaxTotal(sum, taxSum, text(settlement, "InvoiceCurrencyCode"));
        setAmount(sum, grand, "GrandTotalAmount");
        setAmount(sum, due, "DuePayableAmount");
        return new InvoiceData.Totals(taxBasis, taxSum, grand, due);
    }

    /**
     * VAT category for a rate. A special category for 0% (e.g. reverse charge "AE") is kept.
     *
     * @param existing        The category so far, null if new.
     * @param exemptionReason If set, 0% is "exempt" (E), otherwise "zero rated" (Z).
     */
    static String category(String existing, BigDecimal rate, String exemptionReason) {
        if (rate == null)
            return existing == null ? "S" : existing;
        if (rate.signum() > 0)
            return existing != null && Set.of("S", "L", "M").contains(existing) ? existing : "S";
        if (existing == null || Set.of("S", "L", "M", "E", "Z").contains(existing))
            return exemptionReason != null && !exemptionReason.isBlank() ? "E" : "Z";
        return existing;
    }

    /**
     * Writes the sums of an invoice without items (profiles MINIMUM and BASIC WL) from the net amounts per VAT rate.
     * Nothing is changed if there are no amounts.
     *
     * @param breakdown True to write the VAT breakdown and the item total (BASIC WL). MINIMUM only has the totals.
     */
    static InvoiceData.Totals updateWithoutItems(Element root, List<InvoiceData.VatAmount> amounts, boolean breakdown,
                                                 String exemptionReason) {
        Element settlement = ensure(find(root, "SupplyChainTradeTransaction"), "ApplicableHeaderTradeSettlement");
        if (amounts.isEmpty())
            return read(settlement);

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        boolean hasVat = false;
        Map<TaxKey, BigDecimal[]> groups = new LinkedHashMap<>();
        for (InvoiceData.VatAmount a : amounts) {
            BigDecimal n = round(orZero(a.net));
            BigDecimal v = a.vat();
            hasVat |= v != null;
            net = net.add(n);
            vat = vat.add(orZero(v));
            String cat = category(a.category, a.vatPercent, exemptionReason);
            groups.merge(new TaxKey(cat, a.vatPercent), new BigDecimal[]{n, orZero(v)},
                    (x, y) -> new BigDecimal[]{x[0].add(y[0]), x[1].add(y[1])});
        }

        Element sum = ensure(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        if (breakdown) {
            Map<TaxKey, Element> oldTax = new LinkedHashMap<>();
            for (Element t : children(settlement, "ApplicableTradeTax")) {
                oldTax.putIfAbsent(new TaxKey(text(t, "CategoryCode"), decimal(text(t, "RateApplicablePercent"))), t);
                settlement.removeChild(t);
            }
            for (var e : groups.entrySet())
                writeTax(root, settlement, oldTax.get(e.getKey()), e.getKey(), e.getValue()[0], e.getValue()[1], exemptionReason);

            // The item total is the tax basis without document allowances and charges.
            BigDecimal charges = BigDecimal.ZERO;
            BigDecimal allowances = BigDecimal.ZERO;
            for (Element ac : children(settlement, "SpecifiedTradeAllowanceCharge")) {
                BigDecimal amount = round(orZero(decimal(text(ac, "ActualAmount"))));
                if (isCharge(ac))
                    charges = charges.add(amount);
                else
                    allowances = allowances.add(amount);
            }
            setAmount(sum, net.subtract(charges).add(allowances), "LineTotalAmount");
            setOptionalAmount(sum, charges, "ChargeTotalAmount");
            setOptionalAmount(sum, allowances, "AllowanceTotalAmount");
        }

        BigDecimal grand = net.add(vat);
        BigDecimal prepaid = orZero(decimal(text(sum, "TotalPrepaidAmount")));
        BigDecimal rounding = orZero(decimal(text(sum, "RoundingAmount")));
        BigDecimal due = grand.subtract(prepaid).add(rounding);
        setAmount(sum, net, "TaxBasisTotalAmount");
        if (hasVat || !children(sum, "TaxTotalAmount").isEmpty())
            setTaxTotal(sum, vat, text(settlement, "InvoiceCurrencyCode"));
        setAmount(sum, grand, "GrandTotalAmount");
        setAmount(sum, due, "DuePayableAmount");
        return new InvoiceData.Totals(net, vat, grand, due);
    }

    /**
     * Writes one entry of the VAT breakdown. An existing entry keeps its other data (e.g. exemption reason).
     */
    private static void writeTax(Element root, Element settlement, Element old, TaxKey key, BigDecimal base,
                                 BigDecimal amount, String exemptionReason) {
        Element t = old;
        if (t == null) {
            t = CiiXml.create(root.getOwnerDocument(), "ApplicableTradeTax");
            insert(settlement, t);
            set(t, "VAT", "TypeCode");
            set(t, key.category() == null ? "S" : key.category(), "CategoryCode");
            if (!"O".equals(key.category()))
                set(t, orZero(key.rate()).setScale(2, RoundingMode.HALF_UP).toPlainString(), "RateApplicablePercent");
        } else {
            insert(settlement, t);
        }
        setAmount(t, amount, "CalculatedAmount");
        setAmount(t, base, "BasisAmount");
        if (EXEMPT_CATEGORIES.contains(key.category()) && text(t, "ExemptionReasonCode") == null
                && exemptionReason != null && !exemptionReason.isBlank())
            set(t, exemptionReason.trim(), "ExemptionReason");
    }

    /**
     * Net amount of an item: quantity × price (per basis quantity) + charges − allowances, rounded to cents.
     */
    private static BigDecimal lineTotal(Element line, Element lineSettlement) {
        BigDecimal quantity = orZero(decimal(text(line, "SpecifiedLineTradeDelivery", "BilledQuantity")));
        Element price = find(line, "SpecifiedLineTradeAgreement", "NetPriceProductTradePrice");
        BigDecimal amount = orZero(decimal(text(price, "ChargeAmount")));
        BigDecimal basisQuantity = decimal(text(price, "BasisQuantity"));
        if (basisQuantity == null || basisQuantity.signum() == 0)
            basisQuantity = BigDecimal.ONE;
        BigDecimal total = quantity.multiply(amount).divide(basisQuantity, 10, RoundingMode.HALF_UP);
        for (Element ac : children(lineSettlement, "SpecifiedTradeAllowanceCharge")) {
            BigDecimal a = round(orZero(decimal(text(ac, "ActualAmount"))));
            total = isCharge(ac) ? total.add(a) : total.subtract(a);
        }
        return round(total);
    }

    private static boolean isCharge(Element allowanceCharge) {
        return "true".equalsIgnoreCase(text(allowanceCharge, "ChargeIndicator", "Indicator"));
    }

    /**
     * Reads the sums of an invoice without items.
     */
    private static InvoiceData.Totals read(Element settlement) {
        Element sum = find(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        BigDecimal net = orZero(decimal(text(sum, "TaxBasisTotalAmount")));
        BigDecimal vat = BigDecimal.ZERO;
        String currency = text(settlement, "InvoiceCurrencyCode");
        for (Element t : children(sum, "TaxTotalAmount"))
            if (currency == null || !t.hasAttribute("currencyID") || currency.equals(t.getAttribute("currencyID")))
                vat = orZero(decimal(text(t)));
        BigDecimal gross = orZero(decimal(text(sum, "GrandTotalAmount")));
        BigDecimal due = decimal(text(sum, "DuePayableAmount"));
        return new InvoiceData.Totals(net, vat, gross, due == null ? gross : due);
    }

    /**
     * Writes the VAT total in the invoice currency. A second VAT total in another (tax) currency is not touched.
     */
    private static void setTaxTotal(Element sum, BigDecimal value, String currency) {
        Element target = null;
        for (Element t : children(sum, "TaxTotalAmount"))
            if (!t.hasAttribute("currencyID") || Objects.equals(currency, t.getAttribute("currencyID"))) {
                target = t;
                break;
            }
        if (target == null)
            target = insert(sum, CiiXml.create(sum.getOwnerDocument(), "TaxTotalAmount"));
        if (currency != null)
            target.setAttribute("currencyID", currency);
        BigDecimal old = decimal(text(target));
        if (old == null || old.compareTo(value) != 0)
            target.setTextContent(money(value));
    }

    /**
     * Writes an amount. An equal amount in another notation (e.g. "100" for "100.00") is kept.
     */
    private static void setAmount(Element parent, BigDecimal value, String name) {
        BigDecimal old = decimal(text(parent, name));
        if (old == null || old.compareTo(value) != 0)
            set(parent, money(value), name);
    }

    /**
     * Like {@link #setAmount}, but zero is only written if the element exists (the element is optional).
     */
    private static void setOptionalAmount(Element parent, BigDecimal value, String name) {
        if (value.signum() != 0 || find(parent, name) != null)
            setAmount(parent, value, name);
    }

    static BigDecimal decimal(String s) {
        if (s == null)
            return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal orZero(BigDecimal d) {
        return d == null ? BigDecimal.ZERO : d;
    }

    private static BigDecimal round(BigDecimal d) {
        return d.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal d) {
        return round(d).toPlainString();
    }

    /**
     * For tests: all VAT categories of the breakdown.
     */
    static List<String> categories(Element root) {
        List<String> result = new ArrayList<>();
        Element settlement = find(root, "SupplyChainTradeTransaction", "ApplicableHeaderTradeSettlement");
        for (Element t : children(settlement, "ApplicableTradeTax"))
            result.add(text(t, "CategoryCode"));
        return result;
    }
}
