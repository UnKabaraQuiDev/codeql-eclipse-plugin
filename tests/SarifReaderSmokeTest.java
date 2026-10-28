import java.util.List;

import lu.kbra.codeql.sarif.SarifReader;
import lu.kbra.codeql.sarif.SarifReader.Finding;
import lu.kbra.codeql.sarif.SarifReader.Level;

/** Run with: java com.sun.tools.javac.Main -d /tmp/... Json.java SarifReader.java this-file */
public final class SarifReaderSmokeTest {
    public static void main(String[] args) {
        String example = """
            {
              "version":"2.1.0", "runs":[{
                "originalUriBaseIds":{"%SRCROOT%":{"uri":"file:///repo/"}},
                "tool":{"driver":{"rules":[
                  {"id":"java/high", "properties":{"security-severity":"8.2"}},
                  {"id":"java/medium", "properties":{"problem.severity":"warning"}},
                  {"id":"java/low", "defaultConfiguration":{"level":"note"}}
                ]}},
                "results":[
                  {"ruleId":"java/high", "message":{"text":"High issue"},
                   "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/A.java","uriBaseId":"%SRCROOT%"},
                   "region":{"startLine":10,"startColumn":5,"endColumn":8}}}]},
                  {"ruleIndex":1, "message":{"text":"Medium issue"},
                   "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/B.java"}}}]},
                  {"ruleId":"java/low", "level":"note", "message":{"text":"Low issue"},
                   "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/C.java"}}}]}
                ]
              }]}
            """;
        List<Finding> findings = SarifReader.read(example);
        check(findings.size() == 3, "three results parsed");
        check(findings.get(0).level() == Level.HIGH, "high score");
        check(findings.get(1).level() == Level.MEDIUM, "problem severity");
        check(findings.get(2).level() == Level.LOW, "note severity");
        check(findings.get(0).startLine() == 10, "line position");
        check(findings.get(0).startColumn() == 5, "column position");
        check(findings.get(0).baseUri().equals("file:///repo/"), "base URI");
        check(findings.get(1).ruleId().equals("java/medium"), "ruleIndex resolution");
        check(SarifReader.read("{\"runs\":[]}").isEmpty(), "empty report");
        try { SarifReader.read("{invalid}"); throw new AssertionError("bad JSON accepted"); }
        catch (IllegalArgumentException expected) { /* okay */ }
        System.out.println("SARIF smoke tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
