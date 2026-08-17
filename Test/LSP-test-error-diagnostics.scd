(
/*
Test for runtime error diagnostics (textDocument/publishDiagnostics).

Requires the classlib-errors build (or any build where Error.reportError
renders file:// locations with line:col and a source-highlight caret).
*/
UnitTest2({
    |test|
    var provider, sent, mockServer, report, error, doc, uri, func;

    test.section("EvaluateProvider: publishErrorDiagnostics", {
        uri = "file:///tmp/LSP-error-test.scd";

        sent = [];
        mockServer = (
            addDependant: { |self| },
            prSendMessage: { |self, msg| sent = sent.add(msg) }
        );
        provider = EvaluateProvider.new(mockServer, nil);

        // Build a deterministic error whose report references a known file.
        // "1.ownerClass" compiled at line offset 4 renders as line 5 col 13.
        func = thisProcess.interpreter.compile(
            "1.ownerClass", "/tmp/LSP-error-test.scd".asSymbol, 4, 0
        );
        error = nil;
        report = nil;
        try {
            func.value
        } { |e|
            error = e;
            report = String.streamContents({ |s| e.reportError(s) });
        };

        doc = LSPDocument.findByQUuid(uri);
        provider.publishErrorDiagnostics(report, error, doc);

        test.assert(report.notNil, "error report captured");
        test.assert(sent.size == 1, "exactly one message sent");
        if (sent.size == 1) {
            test.equals(sent[0][\method], "textDocument/publishDiagnostics", "method");
        };

        if (sent.size == 1 and: { sent[0][\method] == "textDocument/publishDiagnostics" }) {
            var params, d;
            params = sent[0][\params];
            test.equals(params[\uri], uri, "diagnostic uri");
            test.equals(params[\diagnostics].size, 1, "one diagnostic");
            if (params[\diagnostics].size == 1) {
                d = params[\diagnostics][0];
                test.equals(d[\source], "sclang", "diagnostic source");
                test.equals(d[\severity], 1, "diagnostic severity");
                test.equals(d[\message], "Integer does not understand the message 'ownerClass'.",
                    "diagnostic message");
                test.equals(d[\range][\start][\line], 4, "start line");
                test.equals(d[\range][\start][\character], 0, "start character");
                test.equals(d[\range][\end][\line], 4, "end line");
                test.equals(d[\range][\end][\character], 12, "end character");
            };
        };
    });
}).runOrAdd;
)
