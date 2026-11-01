package com.bw.pdfixit.invoice;

import java.util.List;

/**
 * Operations on the list of party presets that don't need a user interface.
 */
public final class PartyPresets {

    private PartyPresets() {
    }

    /**
     * Finds the preset a party belongs to: the one with the same name and label, else the best match.
     *
     * @return The preset or null.
     */
    public static Party find(List<Party> presets, Party party, Role role) {
        for (Party p : presets)
            if (p.sameIdentity(party))
                return p;
        return Party.findBestMatch(presets, party, role);
    }

    /**
     * Takes over the values of a party of an invoice into the presets: updates the matching entry, or adds a new one.<br>
     * The entry to update is the hint if it is in the list, else the best match of the party.
     * An updated entry keeps its label and roles. A new entry has no label and is offered for the role only.
     *
     * @param hint The preset the party was chosen from, may be null.
     * @return The updated or new entry.
     */
    public static Party applyProposal(List<Party> entries, Party proposal, Party hint, Role role) {
        Party target = null;
        if (hint != null)
            for (Party e : entries)
                if (e.sameIdentity(hint)) {
                    target = e;
                    break;
                }
        if (target == null)
            target = Party.findBestMatch(entries, proposal, role);
        if (target != null) {
            String label = target.label;
            boolean asSeller = target.asSeller;
            boolean asBuyer = target.asBuyer;
            target.copyFrom(proposal);
            target.label = label;
            target.asSeller = asSeller;
            target.asBuyer = asBuyer;
            return target;
        }
        Party added = proposal.copy();
        added.label = null;
        added.asSeller = role == Role.SELLER;
        added.asBuyer = role == Role.BUYER;
        entries.add(added);
        return added;
    }

    /**
     * Adds a copy of the entry with its current values (also unsaved edits) and resets the entry to the original values.
     * The copy gets the label of the entry plus " (copy)", so it is not a duplicate.
     *
     * @param original Values the entry had before editing, null if it is new (then it isn't reset).
     * @return The copy.
     */
    public static Party createCopy(List<Party> entries, Party entry, Party original) {
        Party copy = entry.copy();
        String label = entry.label == null || entry.label.isBlank() ? "" : entry.label.trim() + " ";
        copy.label = label + "(copy)";
        if (original != null)
            entry.copyFrom(original);
        entries.add(entries.indexOf(entry) + 1, copy);
        return copy;
    }

    /**
     * Finds two entries with the same name and label.
     *
     * @return Display name of the first duplicate, null if all are unique.
     */
    public static String findDuplicate(List<Party> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Party a = entries.get(i);
            if (a.name == null || a.name.isBlank())
                continue;
            for (int j = i + 1; j < entries.size(); j++)
                if (a.sameIdentity(entries.get(j)))
                    return a.displayName();
        }
        return null;
    }
}
