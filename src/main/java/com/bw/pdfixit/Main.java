package com.bw.pdfixit;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.cli.CommandExecuter;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.ui.UI;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.awt.geom.Rectangle2D;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * CLI interface. Accepts file names as arguments and opens the UI or executes commands headless.
 */
@Command(name = "PDFixit", mixinStandardHelpOptions = true, version = "PDFixit " + BuildVersion.VERSION,
        resourceBundle = "messages")
public class Main implements Callable<Integer> {

    @Option(names = {"-out", "--out"}, descriptionKey = "cli.out")
    private String out;

    @Option(names = {"-s", "--split"}, paramLabel = "<pages per file>", descriptionKey = "cli.split")
    private int splitToPagesPerFile = 0;

    @Option(names = {"-opw", "--ownerpassword"}, paramLabel = "<password>", descriptionKey = "cli.ownerpassword")
    private String ownerpassword = "";

    @Option(names = {"-upw", "--userpassword"}, paramLabel = "<password>", descriptionKey = "cli.userpassword")
    private String userpassword = "";

    @Option(names = {"-pw", "--originalpassword", "--orginalpassword"}, paramLabel = "<password>", descriptionKey = "cli.originalpassword")
    private String password2Load;

    @Parameters(paramLabel = "PDF File", descriptionKey = "cli.files")
    private List<String> fileArguments;

    @Option(names = {"-cpt", "--canPrint"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canPrint")
    private boolean canPrint = false;

    @Option(names = {"-cmd", "--canModify"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canModify")
    private boolean canModify = false;

    @Option(names = {"-cec", "--canExtractContent"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canExtractContent")
    private boolean canExtractContent = false;

    @Option(names = {"-cea", "--canExtractForAccessibility"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canExtractForAccessibility")
    private boolean canExtractForAccessibility = false;

    @Option(names = {"-cff", "--canFillInForm"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canFillInForm")
    private boolean canFillInForm = false;

    @Option(names = {"-cas", "--canAssemble"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canAssemble")
    private boolean canAssemble = false;

    @Option(names = {"-cma", "--canModifyAnnotations"}, negatable = true, defaultValue = "false", descriptionKey = "cli.canModifyAnnotations")
    private boolean canModifyAnnotations = false;

    @Option(names = {"-h", "--help"}, usageHelp = true, descriptionKey = "cli.help")
    private boolean helpRequested = false;

    @Option(names = {"-m", "--merge"}, paramLabel = "<start>-<amount>-<skip>", descriptionKey = "cli.merge")
    private String mergeOptions = "";

    @Option(names = {"-cert", "--certificate"}, paramLabel = "<p12 file>", descriptionKey = "cli.certificate")
    private String signCertFile = null;

    @Option(names = {"-cpw", "--certificatePassword"}, paramLabel = "<password for p12 file>", descriptionKey = "cli.certificatePassword")
    private String signCertPassword = null;

    @Option(names = {"-rt", "--redactText"}, paramLabel = "<regex>", descriptionKey = "cli.redactText")
    private List<String> redactTexts = new ArrayList<>();

    @Option(names = {"-ra", "--redactArea"}, paramLabel = "<page>:<x>,<y>,<width>,<height>", descriptionKey = "cli.redactArea")
    private List<String> redactAreas = new ArrayList<>();

    @Option(names = {"-ai", "--attachInvoice"}, paramLabel = "<xml file>", descriptionKey = "cli.attachInvoice")
    private String attachInvoice = null;

    @Option(names = {"-ei", "--extractInvoice"}, paramLabel = "<xml file>", descriptionKey = "cli.extractInvoice")
    private String extractInvoice = null;

    @Option(names = {"-ri", "--removeInvoice"}, descriptionKey = "cli.removeInvoice")
    private boolean removeInvoice = false;

    @Option(names = {"-vi", "--validateInvoice"}, descriptionKey = "cli.validateInvoice")
    private boolean validateInvoice = false;

    @Option(names = {"-vp", "--validatePdfa"}, descriptionKey = "cli.validatePdfa")
    private boolean validatePdfa = false;

    @Option(names = {"--debug"}, descriptionKey = "cli.debug")
    private boolean debug = false;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (debug)
            Log.setDebug(true);

        // Only file names: open them in the UI. Any option means CLI (that then needs an output file).
        // "--debug" works for both.
        this.cli = this.out != null || spec.commandLine().getParseResult().matchedOptions().stream()
                .anyMatch(o -> !o.longestName().equals("--debug"));

        if (helpRequested)
            return 0;

        if (this.out != null) {
            this.outfile = Paths.get(this.out);
        }

        if (attachInvoice != null && !Files.exists(Paths.get(attachInvoice))) {
            System.err.println(I18n.t("cli.fileMissing", attachInvoice));
            return 10;
        }

        if (signCertFile != null) {
            signatureFile = Paths.get(signCertFile);
            if (!Files.exists(signatureFile)) {
                System.err.println(I18n.t("cli.fileMissing", signatureFile));
                return 10;
            }
        }

        if (signCertPassword != null)
            signaturePassword = signCertPassword.toCharArray();
        signCertPassword = null;

        if (fileArguments == null)
            return 0;
        for (String f : fileArguments) {
            Path p = Paths.get(f);
            if (Files.exists(p))
                this.files.add(p);
            else {
                System.err.println(I18n.t("cli.fileMissing", f));
            }
        }
        return 0;
    }

    private boolean cli = false;

    private Path outfile;
    private final List<Path> files = new ArrayList<>();

    private Path signatureFile;
    private char[] signaturePassword;

    public boolean isCli() {
        return cli;
    }

    public boolean isHelpRequested() {
        return helpRequested;
    }

    public static void main(String[] args) {
        I18n.init();

        Main main = new Main();
        if (args.length > 0) {
            int exitCode = new CommandLine(main).execute(args);
            if (exitCode != 0 || main.helpRequested)
                System.exit(exitCode);
            else if (main.isCli())
                System.exit(main.executeCommands());
            else
                main.createUI();
        } else {
            main.createUI();
        }
    }

    public void usage(PrintStream os) {
        spec.commandLine().usage(os);
    }

    /**
     * Execute the commands from CLI.
     *
     * @return The exit code (0 for success).
     */
    protected int executeCommands() {

        if (files.isEmpty()) {
            System.err.println(I18n.t("cli.noFiles"));
            usage(System.err);
            return 2;
        }
        if (outfile == null) {
            System.err.println(I18n.t("cli.noOutput"));
            usage(System.err);
            return 3;
        }
        CommandExecuter executer = new CommandExecuter(files);
        try {
            MergeOptions mo = null;
            if (!mergeOptions.isEmpty()) {
                String[] mergeParams = mergeOptions.split("-");
                if (mergeParams.length != 3) {
                    System.err.println(I18n.t("cli.mergeError"));
                    usage(System.err);
                    return 4;
                }
                mo = new MergeOptions();
                try {
                    mo.startPageNb = Integer.parseInt(mergeParams[0]);
                    mo.segmentLength = Integer.parseInt(mergeParams[1]);
                    mo.gapLength = Integer.parseInt(mergeParams[2]);
                } catch (NumberFormatException ne) {
                    System.err.println(I18n.t("cli.mergeNumber"));
                    usage(System.err);
                    return 5;
                }
                System.out.println(I18n.t("cli.merging", mo));
            }

            List<Pattern> textPatterns = new ArrayList<>();
            List<Redactor.Region> areas = new ArrayList<>();
            try {
                for (String t : redactTexts)
                    textPatterns.add(Pattern.compile(t));
                for (String a : redactAreas)
                    areas.add(parseRedactArea(a));
            } catch (IllegalArgumentException e) {
                System.err.println(I18n.t("cli.redactError", e.getMessage()));
                usage(System.err);
                return 6;
            }

            if ((validateInvoice || validatePdfa) && splitToPagesPerFile > 0) {
                System.err.println(I18n.t("cli.validateSplit"));
                usage(System.err);
                return 7;
            }

            // TODO: Currently all input files are merged and protected.
            try {
                executer.loadDocuments(password2Load, mo);
            } catch (CommandExecuter.LoadException e) {
                System.err.println(e.getMessage());
                return 14;
            }

            final boolean hasInvoice = executer.hasInvoice();

            if (extractInvoice != null) {
                if (executer.extractInvoice(Paths.get(extractInvoice)))
                    System.out.println(I18n.t("cli.invoiceStored", extractInvoice));
                else {
                    System.err.println(I18n.t("cli.noInvoiceErr"));
                    return 8;
                }
            }

            if (hasInvoice && !removeInvoice && (!textPatterns.isEmpty() || !areas.isEmpty())) {
                System.err.println(I18n.t("cli.redactInvoice"));
                return 9;
            }

            if (!textPatterns.isEmpty() || !areas.isEmpty()) {
                System.out.println(I18n.t("cli.redacted", executer.redact(textPatterns, areas)));
            }

            if (removeInvoice) {
                if (executer.removeInvoice())
                    System.out.println(I18n.t("cli.invoiceRemoved"));
                else
                    System.out.println(I18n.t("cli.noInvoiceOut"));
            }

            if (attachInvoice != null) {
                try {
                    executer.attachInvoice(Paths.get(attachInvoice));
                } catch (EInvoice.NotConformException e) {
                    System.err.println(e.getMessage());
                    return 13;
                }
                System.out.println(I18n.t("cli.invoiceAttached", attachInvoice));
            }

            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(canPrint);
            ap.setCanModify(canModify);
            ap.setCanExtractContent(canExtractContent);
            ap.setCanExtractForAccessibility(canExtractForAccessibility);
            ap.setCanFillInForm(canFillInForm);
            ap.setCanAssembleDocument(canAssemble);
            ap.setCanModifyAnnotations(canModifyAnnotations);

            // TODO: Add cli for that
            int encryptionKeyLength = 256;

            if (ownerpassword.isEmpty() && !userpassword.isEmpty()) {
                ownerpassword = UUID.randomUUID().toString();
                System.out.println(I18n.t("cli.ownerGenerated", ownerpassword));
            }

            if (this.splitToPagesPerFile > 0) {
                executer.split(this.splitToPagesPerFile,
                        ownerpassword, userpassword, ap, encryptionKeyLength, outfile,
                        signatureFile, signaturePassword);

            } else {
                executer.save(ownerpassword, userpassword, ap, encryptionKeyLength, outfile,
                        signatureFile, signaturePassword);
            }

            if (validateInvoice) {
                EInvoice.Validation v;
                try {
                    v = executer.validateInvoice(outfile);
                } catch (Exception e) {
                    System.err.println(I18n.t("cli.validationFailed", e.getMessage()));
                    return 11;
                }
                v.messages().forEach(System.err::println);
                if (!v.valid()) {
                    System.err.println(I18n.t("cli.invoiceInvalid"));
                    return 12;
                }
                System.out.println(I18n.t("cli.invoiceValid"));
            }

            if (validatePdfa) {
                ValidationResult result;
                try {
                    result = executer.validatePdfa(outfile);
                } catch (Exception e) {
                    System.err.println(I18n.t("cli.validationFailed", e.getMessage()));
                    return 11;
                }
                result.toLines().forEach(System.err::println);
                if (!result.isConform()) {
                    System.err.println(I18n.t("cli.pdfaInvalid"));
                    return 15;
                }
                System.out.println(I18n.t("cli.pdfaValid"));
            }


        } catch (Exception e) {
            Log.exception(e);
            return 1;
        } finally {
            executer.close();
        }
        return 0;
    }

    /**
     * Parses "page:x,y,width,height".
     *
     * @throws IllegalArgumentException If the argument is malformed (including NumberFormatException).
     */
    static Redactor.Region parseRedactArea(String arg) {
        String[] pageAndArea = arg.split(":");
        String[] v = pageAndArea.length == 2 ? pageAndArea[1].split(",") : new String[0];
        if (v.length != 4)
            throw new IllegalArgumentException(I18n.t("cli.areaMismatch", arg));
        int page = Integer.parseInt(pageAndArea[0].trim());
        double x = Double.parseDouble(v[0].trim());
        double y = Double.parseDouble(v[1].trim());
        double w = Double.parseDouble(v[2].trim());
        double h = Double.parseDouble(v[3].trim());
        return new Redactor.Region(page, new Rectangle2D.Double(x, y, w, h));
    }

    /**
     * Creates the user interface anew in this process, e.g. after the language was changed: the language is read
     * again and the document of the closed user interface is carried over.
     *
     * @param carry The document, may be null.
     */
    public static void restartUI(UI.Carry carry) {

        I18n.init();
        UI.resetLocalizedState();
        UI.createUI(List.of(), carry);
        // Run later, force pending disposals of the old UI.
        javax.swing.SwingUtilities.invokeLater(System::gc);
    }

    protected void createUI() {
        UI.createUI(files);
    }

}
