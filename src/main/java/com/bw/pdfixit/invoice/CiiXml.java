package com.bw.pdfixit.invoice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Access to the XML of a CII invoice (UN/CEFACT Cross Industry Invoice, as used by ZUGFeRD 2, Factur-X and XRechnung).<br>
 * Elements are addressed by their local names. New elements are inserted at the position the schema requires.
 */
final class CiiXml {

    static final String RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
    static final String RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
    static final String UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";
    static final String QDT = "urn:un:unece:uncefact:data:standard:QualifiedDataType:100";

    static final String ROOT = "CrossIndustryInvoice";

    private static final Set<String> RSM_ELEMENTS = Set.of(
            "ExchangedDocumentContext", "ExchangedDocument", "SupplyChainTradeTransaction");

    /**
     * Order of the child elements (CII D16B), for the elements that are created by the editor.
     * Children not listed here are never moved.
     */
    private static final Map<String, List<String>> ORDER = Map.ofEntries(
            Map.entry(ROOT, List.of("ExchangedDocumentContext", "ExchangedDocument", "SupplyChainTradeTransaction")),
            Map.entry("ExchangedDocumentContext", List.of("TestIndicator",
                    "BusinessProcessSpecifiedDocumentContextParameter", "GuidelineSpecifiedDocumentContextParameter")),
            Map.entry("ExchangedDocument", List.of("ID", "Name", "TypeCode", "IssueDateTime", "CopyIndicator",
                    "LanguageID", "IncludedNote", "EffectiveSpecifiedPeriod")),
            Map.entry("IncludedNote", List.of("ContentCode", "Content", "SubjectCode")),
            Map.entry("SupplyChainTradeTransaction", List.of("IncludedSupplyChainTradeLineItem",
                    "ApplicableHeaderTradeAgreement", "ApplicableHeaderTradeDelivery", "ApplicableHeaderTradeSettlement")),
            Map.entry("IncludedSupplyChainTradeLineItem", List.of("AssociatedDocumentLineDocument",
                    "SpecifiedTradeProduct", "SpecifiedLineTradeAgreement", "SpecifiedLineTradeDelivery",
                    "SpecifiedLineTradeSettlement")),
            Map.entry("AssociatedDocumentLineDocument", List.of("LineID", "ParentLineID", "LineStatusCode",
                    "LineStatusReasonCode", "IncludedNote")),
            Map.entry("SpecifiedTradeProduct", List.of("ID", "GlobalID", "SellerAssignedID", "BuyerAssignedID",
                    "IndustryAssignedID", "ModelID", "Name", "Description")),
            Map.entry("SpecifiedLineTradeAgreement", List.of("BuyerReference", "BuyerOrderReferencedDocument",
                    "QuotationReferencedDocument", "ContractReferencedDocument", "AdditionalReferencedDocument",
                    "GrossPriceProductTradePrice", "NetPriceProductTradePrice")),
            Map.entry("NetPriceProductTradePrice", List.of("ChargeAmount", "BasisQuantity",
                    "AppliedTradeAllowanceCharge", "IncludedTradeTax")),
            Map.entry("SpecifiedLineTradeDelivery", List.of("BilledQuantity", "ChargeFreeQuantity", "PackageQuantity")),
            Map.entry("SpecifiedLineTradeSettlement", List.of("ApplicableTradeTax", "BillingSpecifiedPeriod",
                    "SpecifiedTradeAllowanceCharge", "SpecifiedTradeSettlementLineMonetarySummation",
                    "InvoiceReferencedDocument", "AdditionalReferencedDocument",
                    "ReceivableSpecifiedTradeAccountingAccount")),
            Map.entry("ApplicableTradeTax", List.of("CalculatedAmount", "TypeCode", "ExemptionReason", "BasisAmount",
                    "LineTotalBasisAmount", "AllowanceChargeBasisAmount", "CategoryCode", "ExemptionReasonCode",
                    "TaxPointDate", "DueDateTypeCode", "RateApplicablePercent")),
            Map.entry("SpecifiedTradeSettlementLineMonetarySummation", List.of("LineTotalAmount", "ChargeTotalAmount",
                    "AllowanceTotalAmount", "TaxTotalAmount", "GrandTotalAmount", "TotalAllowanceChargeAmount")),
            Map.entry("ApplicableHeaderTradeAgreement", List.of("BuyerReference", "SellerTradeParty", "BuyerTradeParty",
                    "SalesAgentTradeParty", "BuyerTaxRepresentativeTradeParty", "SellerTaxRepresentativeTradeParty",
                    "ProductEndUserTradeParty", "ApplicableTradeDeliveryTerms", "SellerOrderReferencedDocument",
                    "BuyerOrderReferencedDocument", "QuotationReferencedDocument", "ContractReferencedDocument",
                    "AdditionalReferencedDocument", "BuyerAgentTradeParty", "SpecifiedProcuringProject",
                    "UltimateCustomerOrderReferencedDocument")),
            Map.entry("SellerTradeParty", tradePartyOrder()),
            Map.entry("BuyerTradeParty", tradePartyOrder()),
            Map.entry("DefinedTradeContact", List.of("PersonName", "DepartmentName", "TypeCode",
                    "TelephoneUniversalCommunication", "FaxUniversalCommunication", "EmailURIUniversalCommunication")),
            Map.entry("PostalTradeAddress", List.of("PostcodeCode", "LineOne", "LineTwo", "LineThree", "CityName",
                    "CountryID", "CountrySubDivisionName")),
            Map.entry("ApplicableHeaderTradeDelivery", List.of("RelatedSupplyChainConsignment", "ShipToTradeParty",
                    "UltimateShipToTradeParty", "ShipFromTradeParty", "ActualDeliverySupplyChainEvent",
                    "DespatchAdviceReferencedDocument", "ReceivingAdviceReferencedDocument",
                    "DeliveryNoteReferencedDocument")),
            Map.entry("ApplicableHeaderTradeSettlement", List.of("CreditorReferenceID", "PaymentReference",
                    "TaxCurrencyCode", "InvoiceCurrencyCode", "InvoiceIssuerReference", "InvoicerTradeParty",
                    "InvoiceeTradeParty", "PayeeTradeParty", "TaxApplicableTradeCurrencyExchange",
                    "SpecifiedTradeSettlementPaymentMeans", "ApplicableTradeTax", "BillingSpecifiedPeriod",
                    "SpecifiedTradeAllowanceCharge", "SpecifiedLogisticsServiceCharge", "SpecifiedTradePaymentTerms",
                    "SpecifiedTradeSettlementHeaderMonetarySummation", "InvoiceReferencedDocument",
                    "ReceivableSpecifiedTradeAccountingAccount", "SpecifiedAdvancePayment")),
            Map.entry("SpecifiedTradeSettlementPaymentMeans", List.of("TypeCode", "Information",
                    "ApplicableTradeSettlementFinancialCard", "PayerPartyDebtorFinancialAccount",
                    "PayeePartyCreditorFinancialAccount", "PayeeSpecifiedCreditorFinancialInstitution")),
            Map.entry("PayeePartyCreditorFinancialAccount", List.of("IBANID", "AccountName", "ProprietaryID")),
            Map.entry("BillingSpecifiedPeriod", List.of("Description", "StartDateTime", "EndDateTime",
                    "CompleteDateTime")),
            Map.entry("SpecifiedTradePaymentTerms", List.of("Description", "DueDateDateTime", "DirectDebitMandateID",
                    "PartialPaymentAmount", "ApplicableTradePaymentPenaltyTerms", "ApplicableTradePaymentDiscountTerms",
                    "PayeeTradeParty")),
            Map.entry("SpecifiedTradeSettlementHeaderMonetarySummation", List.of("LineTotalAmount", "ChargeTotalAmount",
                    "AllowanceTotalAmount", "TaxBasisTotalAmount", "TaxTotalAmount", "RoundingAmount",
                    "GrandTotalAmount", "TotalPrepaidAmount", "DuePayableAmount")));

    private static List<String> tradePartyOrder() {
        return List.of("ID", "GlobalID", "Name", "RoleCode", "Description", "SpecifiedLegalOrganization",
                "DefinedTradeContact", "PostalTradeAddress", "URIUniversalCommunication", "SpecifiedTaxRegistration");
    }

    private CiiXml() {
    }

    // ---------------------------------------------------------------- documents

    /**
     * Creates an empty invoice with the elements every CII invoice has.
     */
    static Document newDocument() {
        Document doc = newBuilder().newDocument();
        Element root = doc.createElementNS(RSM, "rsm:" + ROOT);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:rsm", RSM);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ram", RAM);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:udt", UDT);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:qdt", QDT);
        doc.appendChild(root);
        ensure(root, "ExchangedDocumentContext");
        ensure(root, "ExchangedDocument");
        Element tx = ensure(root, "SupplyChainTradeTransaction");
        ensure(tx, "ApplicableHeaderTradeAgreement");
        ensure(tx, "ApplicableHeaderTradeDelivery");
        ensure(tx, "ApplicableHeaderTradeSettlement");
        return doc;
    }

    /**
     * Parses an invoice. White space between elements is removed, the output is indented again.
     *
     * @throws IOException If it's no XML or no CII invoice.
     */
    static Document parse(byte[] xml) throws IOException {
        Document doc = parseXml(xml);
        Element root = doc.getDocumentElement();
        if (!ROOT.equals(root.getLocalName()) || !RSM.equals(root.getNamespaceURI()))
            throw new IOException("Only CII invoices (ZUGFeRD 2, Factur-X, XRechnung CII) can be edited, found <"
                    + root.getNodeName() + ">.");
        return doc;
    }

    /**
     * Parses any XML. White space between elements is removed.
     */
    private static Document parseXml(byte[] xml) throws IOException {
        Document doc;
        try {
            DocumentBuilder builder = newBuilder();
            // Don't print parse errors to the console.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            doc = builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("The invoice XML can't be read: " + e.getMessage(), e);
        }
        removeWhitespace(doc.getDocumentElement());
        return doc;
    }

    /**
     * Indents any XML for display.
     *
     * @return The indented XML, or the XML as it is if it can't be parsed.
     */
    static String pretty(byte[] xml) {
        try {
            return new String(serialize(parseXml(xml)), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return new String(xml, StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes the invoice as indented UTF-8 XML.
     */
    static byte[] serialize(Document doc) {
        try {
            // The JDK's own transformer: others on the class path (e.g. Saxon) handle the indent options differently.
            Transformer t = TransformerFactory.newDefaultInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(os));
            // The transformer uses the line separator of the platform.
            String xml = os.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
            return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xml + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write the invoice XML", e);
        }
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void removeWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; --i) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()
                    && hasElementChildren(node))
                node.removeChild(child);
            else
                removeWhitespace(child);
        }
    }

    private static boolean hasElementChildren(Node node) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling())
            if (c.getNodeType() == Node.ELEMENT_NODE)
                return true;
        return false;
    }

    // ---------------------------------------------------------------- navigation

    /**
     * The child elements with this local name.
     */
    static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null)
            return result;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e && localName.equals(e.getLocalName()))
                result.add(e);
        return result;
    }

    /**
     * All child elements.
     */
    static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e)
                result.add(e);
        return result;
    }

    /**
     * Follows the path (first match at each step).
     *
     * @return The element or null.
     */
    static Element find(Element parent, String... path) {
        Element e = parent;
        for (String name : path) {
            if (e == null)
                return null;
            List<Element> c = children(e, name);
            e = c.isEmpty() ? null : c.get(0);
        }
        return e;
    }

    /**
     * Trimmed text of the element at the path.
     *
     * @return The text, null if missing or blank.
     */
    static String text(Element parent, String... path) {
        Element e = find(parent, path);
        if (e == null)
            return null;
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    // ---------------------------------------------------------------- changes

    /**
     * Creates the elements of the path that are missing.
     *
     * @return The last element of the path.
     */
    static Element ensure(Element parent, String... path) {
        Element e = parent;
        for (String name : path) {
            Element c = find(e, name);
            e = c == null ? insert(e, create(e.getOwnerDocument(), name)) : c;
        }
        return e;
    }

    /**
     * Creates an element with the namespace CII uses for this name.
     */
    static Element create(Document doc, String localName) {
        String ns = RSM_ELEMENTS.contains(localName) || ROOT.equals(localName) ? RSM
                : "DateTimeString".equals(localName) ? UDT : RAM;
        String prefix = doc.getDocumentElement() == null ? null : doc.getDocumentElement().lookupPrefix(ns);
        if (prefix == null) {
            prefix = ns.equals(RSM) ? "rsm" : ns.equals(UDT) ? "udt" : "ram";
            if (doc.getDocumentElement() != null)
                doc.getDocumentElement().setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, ns);
        }
        return doc.createElementNS(ns, prefix + ":" + localName);
    }

    /**
     * Inserts the element as child at the position the schema requires: behind the existing children
     * that come before it (or have the same name).
     *
     * @return The element.
     */
    static Element insert(Element parent, Element child) {
        List<String> order = ORDER.get(parent.getLocalName());
        Node before = null;
        if (order != null) {
            int idx = order.indexOf(child.getLocalName());
            if (idx >= 0) {
                for (Element c : children(parent)) {
                    int ci = order.indexOf(c.getLocalName());
                    if (ci > idx && c != child) {
                        before = c;
                        break;
                    }
                }
            }
        }
        parent.insertBefore(child, before);
        return child;
    }

    /**
     * Sets the text of the element at the path. The elements are created if needed.
     * Null or blank removes the element (and parents that get empty, up to the parent).
     * Nothing is changed if the text is already the same, so the original formatting is kept.
     *
     * @return The element, null if removed.
     */
    static Element set(Element parent, String value, String... path) {
        String v = value == null || value.isBlank() ? null : value.trim();
        if (Objects.equals(text(parent, path), v))
            return find(parent, path);
        if (v == null) {
            remove(parent, path);
            return null;
        }
        Element e = ensure(parent, path);
        e.setTextContent(v);
        return e;
    }

    /**
     * Removes the element at the path and parents that get empty, up to (not including) the parent.
     */
    static void remove(Element parent, String... path) {
        Element e = find(parent, path);
        if (e != null)
            removeAndPrune(e, parent);
    }

    /**
     * Removes the element and parents that get empty, up to (not including) the stop element.
     */
    static void removeAndPrune(Element e, Element stop) {
        Node p = e.getParentNode();
        p.removeChild(e);
        while (p instanceof Element pe && pe != stop && children(pe).isEmpty()
                && (pe.getTextContent() == null || pe.getTextContent().isBlank())) {
            Node next = pe.getParentNode();
            if (next == null)
                break;
            next.removeChild(pe);
            p = next;
        }
    }
}
