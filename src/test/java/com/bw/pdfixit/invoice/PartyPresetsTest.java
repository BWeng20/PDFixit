package com.bw.pdfixit.invoice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loading and saving the party presets of {@link InvoiceData}: one list for sellers and buyers.
 */
class PartyPresetsTest {

    @TempDir
    Path dir;

    @Test
    void file() {
        Assertions.assertEquals("parties.json", InvoiceData.partiesFile().getFileName().toString());
        Assertions.assertEquals(InvoiceData.settingsDirectory(), InvoiceData.partiesFile().getParent());
    }

    @Test
    void missingFileIsEmpty() throws Exception {
        Assertions.assertTrue(InvoiceData.loadParties(dir.resolve("parties.json")).isEmpty());
    }

    @Test
    void saveAndLoad() throws Exception {
        Party a = InvoiceDataTest.sample().seller;
        a.asBuyer = false;
        Party b = InvoiceDataTest.sample().buyer;
        b.asSeller = false;
        Party both = new Party();
        both.name = "Second Testfirma";
        both.country = "AT";

        // The directory is created.
        Path file = dir.resolve("settings").resolve("parties.json");
        InvoiceData.saveParties(file, List.of(a, b, both));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Assertions.assertTrue(json.contains("\"parties\""), json);
        Assertions.assertFalse(json.contains("null"), "empty values are not written: " + json);
        Assertions.assertTrue(json.contains("\"asBuyer\" : false"), "false flags are written: " + json);

        List<Party> loaded = InvoiceData.loadParties(file);
        Assertions.assertEquals(3, loaded.size());
        Party la = loaded.get(0);
        Assertions.assertEquals(a.name, la.name);
        Assertions.assertEquals(a.city, la.city);
        Assertions.assertEquals(a.vatId, la.vatId);
        Assertions.assertEquals(a.iban, la.iban);
        Assertions.assertEquals(a.contactName, la.contactName);
        Assertions.assertEquals("AT", loaded.get(2).country);
        Assertions.assertNull(loaded.get(2).street);

        // Roles
        Assertions.assertTrue(la.hasRole(Role.SELLER));
        Assertions.assertFalse(la.hasRole(Role.BUYER));
        Assertions.assertFalse(loaded.get(1).hasRole(Role.SELLER));
        Assertions.assertTrue(loaded.get(1).hasRole(Role.BUYER));
        Assertions.assertTrue(loaded.get(2).hasRole(Role.SELLER) && loaded.get(2).hasRole(Role.BUYER),
                "new entries are offered in both roles");
    }

    @Test
    void missingFlagsMeanBothRoles() throws Exception {
        Path file = dir.resolve("parties.json");
        Files.writeString(file, "{\"parties\":[{\"name\":\"X\"}]}");
        Party x = InvoiceData.loadParties(file).get(0);
        Assertions.assertTrue(x.asSeller && x.asBuyer);
    }

    @Test
    void otherContentIsKept() throws Exception {
        Path file = dir.resolve("parties.json");
        Files.writeString(file, "{\"version\":2,\"parties\":[{\"name\":\"X\",\"future\":1}]}");
        Assertions.assertEquals("X", InvoiceData.loadParties(file).get(0).name, "unknown properties are ignored");

        Party y = new Party();
        y.name = "Y";
        InvoiceData.saveParties(file, List.of(y));
        String json = Files.readString(file);
        Assertions.assertTrue(json.contains("\"version\" : 2"), json);
        Assertions.assertEquals("Y", InvoiceData.loadParties(file).get(0).name);
    }

    @Test
    void brokenFile() throws Exception {
        Path file = dir.resolve("parties.json");
        Files.writeString(file, "{ not json");
        Assertions.assertThrows(IOException.class, () -> InvoiceData.loadParties(file));

        // Saving replaces it.
        Party y = new Party();
        y.name = "Y";
        InvoiceData.saveParties(file, List.of(y));
        Assertions.assertEquals("Y", InvoiceData.loadParties(file).get(0).name);
    }

    private static Party party(String name, String label, String street, String city) {
        Party p = new Party();
        p.name = name;
        p.label = label;
        p.street = street;
        p.city = city;
        return p;
    }

    @Test
    void labelRoundTrip() throws Exception {
        Path file = dir.resolve("parties.json");
        InvoiceData.saveParties(file, List.of(party("ACME", "Berlin", null, null), party("Old", null, null, null)));
        List<Party> loaded = InvoiceData.loadParties(file);
        Assertions.assertEquals("Berlin", loaded.get(0).label);
        Assertions.assertNull(loaded.get(1).label);
        Assertions.assertEquals("ACME - Berlin", loaded.get(0).displayName());
        Assertions.assertEquals("Old", loaded.get(1).displayName());
        Assertions.assertFalse(Files.readString(file).contains("displayName"));
    }

    @Test
    void bestMatch() {
        Party berlin = party("ACME", "Berlin", "Main St 1", "Berlin");
        Party munich = party("ACME", "Munich", "Side St 2", "Munich");
        Party other = party("Other", null, "Main St 1", "Berlin");
        List<Party> all = List.of(other, berlin, munich);

        Assertions.assertSame(munich, Party.findBestMatch(all, party("acme ", null, "Side St 2", null), null));
        // An edited field still finds the version with the most equal values.
        Assertions.assertSame(munich, Party.findBestMatch(all, party("ACME", null, "Changed", "Munich"), null));
        Assertions.assertSame(berlin, Party.findBestMatch(all, party("ACME", null, "Main St 1", "Berlin"), null));
        Assertions.assertNull(Party.findBestMatch(all, party("Unknown", null, "Main St 1", "Berlin"), null));
        Assertions.assertNull(Party.findBestMatch(all, party(" ", null, null, null), null));

        // No equal values: the first one, unless the role decides.
        Assertions.assertSame(berlin, Party.findBestMatch(all, party("ACME", null, null, null), Role.SELLER));
        munich.asBuyer = true;
        berlin.asBuyer = false;
        Assertions.assertSame(munich, Party.findBestMatch(all, party("ACME", null, null, null), Role.BUYER));

        // Fewer conflicts win at an equal count.
        Party a = party("Z", "a", "S", "C1");
        Party b = party("Z", "b", "S", null);
        Assertions.assertSame(b, Party.findBestMatch(List.of(a, b), party("Z", null, "S", "C2"), null));

        Party iban = party("I", null, null, null);
        iban.iban = "DE02 1203 0000 0000 2020 51";
        Party probe = party("I", null, null, null);
        probe.iban = "de02120300000000202051";
        Assertions.assertSame(iban, Party.findBestMatch(List.of(party("I", "x", "S", null), iban), probe, null));
    }

    @Test
    void findPrefersIdentity() {
        Party a = party("ACME", "A", "S", "C");
        Party b = party("ACME", "B", "S", "C");
        Party probe = b.copy();
        Assertions.assertSame(b, PartyPresets.find(List.of(a, b), probe, null));
        probe.label = null;
        Assertions.assertSame(a, PartyPresets.find(List.of(a, b), probe, null));
    }

    @Test
    void applyProposalUpdates() {
        Party a = party("ACME", "A", "Old St", "C");
        a.asBuyer = false;
        Party b = party("ACME", "B", "New St", "C");
        List<Party> list = new java.util.ArrayList<>(List.of(a, b));

        // The hint wins over the better match.
        Party proposal = party("ACME", null, "New St", "Other");
        Assertions.assertSame(a, PartyPresets.applyProposal(list, proposal, a.copy(), Role.BUYER));
        Assertions.assertEquals("Other", a.city);
        Assertions.assertEquals("A", a.label);
        Assertions.assertFalse(a.asBuyer, "roles are kept");
        Assertions.assertEquals(2, list.size());

        // Without hint the best match is updated.
        proposal = party("ACME", null, "New St", "C");
        proposal.email = "x@y.de";
        Assertions.assertSame(b, PartyPresets.applyProposal(list, proposal, null, Role.SELLER));
        Assertions.assertEquals("x@y.de", b.email);
    }

    @Test
    void applyProposalCreates() {
        List<Party> list = new java.util.ArrayList<>(List.of(party("ACME", "A", null, null)));
        Party proposal = party("New", "ignored", "S", "C");
        Party added = PartyPresets.applyProposal(list, proposal, null, Role.BUYER);
        Assertions.assertEquals(2, list.size());
        Assertions.assertSame(added, list.get(1));
        Assertions.assertNotSame(proposal, added);
        Assertions.assertEquals("S", added.street);
        Assertions.assertNull(added.label);
        Assertions.assertTrue(added.asBuyer);
        Assertions.assertFalse(added.asSeller);
    }

    @Test
    void createCopyResetsSource() {
        Party original = party("ACME", "A", "Old", "C");
        Party edited = original.copy();
        edited.street = "Edited";
        List<Party> list = new java.util.ArrayList<>(List.of(edited, party("Z", null, null, null)));
        Party copy = PartyPresets.createCopy(list, edited, original);
        Assertions.assertEquals(3, list.size());
        Assertions.assertSame(copy, list.get(1));
        Assertions.assertEquals("Edited", copy.street);
        Assertions.assertEquals("A (copy)", copy.label);
        Assertions.assertEquals("Old", edited.street, "source is reset");
        Assertions.assertNull(PartyPresets.findDuplicate(list));

        // A new entry has no original: it is not reset.
        Party fresh = party("N", null, "S", null);
        list.add(fresh);
        Party c2 = PartyPresets.createCopy(list, fresh, null);
        Assertions.assertEquals("S", fresh.street);
        Assertions.assertEquals("(copy)", c2.label);
    }

    @Test
    void sameData() {
        Party a = party("ACME", "A", "S", "C");
        Party b = a.copy();
        Assertions.assertTrue(a.sameData(b));
        b.street = "T";
        Assertions.assertFalse(a.sameData(b));
        b = a.copy();
        b.asBuyer = false;
        Assertions.assertFalse(a.sameData(b));

        // Updating with the values the entry already has changes nothing.
        Party same = a.copy();
        List<Party> list = new java.util.ArrayList<>(List.of(a));
        Party before = a.copy();
        Assertions.assertSame(a, PartyPresets.applyProposal(list, same, null, Role.SELLER));
        Assertions.assertTrue(a.sameData(before));
    }

    @Test
    void duplicates() {
        Party a = party("ACME", "A", null, null);
        Party b = party("acme", " a ", null, null);
        Assertions.assertEquals("ACME - A", PartyPresets.findDuplicate(List.of(a, b)));
        b.label = "B";
        Assertions.assertNull(PartyPresets.findDuplicate(List.of(a, b)));
        Assertions.assertNull(PartyPresets.findDuplicate(List.of(party(null, null, null, null), party(null, null, null, null))));
    }

    @Test
    void copy() {
        Party a = InvoiceDataTest.sample().seller;
        a.asBuyer = false;
        Party c = a.copy();
        Assertions.assertNotSame(a, c);
        Assertions.assertEquals(a.name, c.name);
        Assertions.assertEquals(a.iban, c.iban);
        Assertions.assertEquals(a.phone, c.phone);
        Assertions.assertFalse(c.asBuyer);
    }
}
