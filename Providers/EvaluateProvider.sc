// https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_implementation
EvaluateProvider : LSPProvider {
    classvar
        <>resultStringLimit = 2000,
        <>sourceCodeLineLimit=6,
        <>skipErrorConstructors=true,
        <>evaluateEnvironment;

    var resultPrefix="> ";
    var guestUserPrefix="[%|> ";
    var postResult=true;
    var <>postBeforeEvaluate="", <>postAfterEvaluate="";

    *methodNames {
        ^[
            "textDocument/evaluateSelection",
        ]
    }
    *clientCapabilityName { ^"textDocument.evaluation" }
    *serverCapabilityName { ^"evaluationProvider" }

    init {
        |clientCapabilities|
        server.addDependant({
            |server, message, value|
            if (message == \clientOptions) {
                resultPrefix = value['sclang.evaluateResultPrefix'] ?? {"> "};
                guestUserPrefix = value['sclang.guestEvaluateResultPrefix'] ?? {"[%|> "};
                postResult = value['sclang.postEvaluateResults'] !? (_ == "true") ?? true;
            }
        })
    }

    options {
        ^()
    }

    doEvaluate {
        |func|
        var result = func.value();
        ^result;
    }

    captureErrorReport {
        |error|
        var report;
        try {
            report = String.streamContents({ |stream| error.reportError(stream) });
        } {
            |e|
            Log('LanguageServer.quark').warning("Failed to capture error report: %", e);
            report = error.what ? "";
        };
        ^report
    }

    // Parse the improved error report produced by Exception:reportError for
    // file:// locations. Returns the innermost location that maps to an open
    // document, falling back to the innermost real-file location; nil if the
    // report has no usable locations (e.g. only interpreted_text).
    primaryErrorLocation {
        |report|
        var lines, locations, lastLoc, openUris, fallback;
        lines = report.split(Char.nl);
        locations = [];
        lastLoc = nil;
        lines.do { |line|
            var m = line.findRegexp("^\\s*file://(.+):(\\d+):(\\d+)\\s*$");
            if (m.size > 0) {
                lastLoc = (
                    path: m[1][1],
                    line: m[2][1].asInteger,
                    col: m[3][1].asInteger,
                    span: 1
                );
                locations = locations.add(lastLoc);
            } {
                var caret = line.findRegexp("┆\\s*(\\^+)");
                if (caret.size > 0 and: { lastLoc.notNil }) {
                    lastLoc[\span] = caret[1][1].size;
                };
            };
        };

        openUris = LSPDocument.openDocuments !? { |docs|
            docs.collect({ |doc| doc.quuid })
        } ?? { [] };

        fallback = nil;
        locations.reverse.do { |loc|
            if (loc[\path] != "interpreted_text") {
                if (openUris.includes(loc[\path].pathToFileURI)) {
                    ^loc
                };
                fallback = fallback ?? { loc };
            };
        };
        ^fallback
    }

    publishErrorDiagnostics {
        |report, error, document|
        var location, diag, uri, line, endChar, startChar;
        location = this.primaryErrorLocation(report);
        if (location.notNil) {
            line = location[\line] - 1;
            endChar = location[\col] - 1;
            startChar = max(0, endChar - location[\span]);
            uri = location[\path].pathToFileURI;
            diag = (
                range: (
                    start: (line: line, character: startChar),
                    end: (line: line, character: endChar)
                ),
                severity: 1,
                source: "sclang",
                message: error.what
            );
            this.sendDiagnostics(uri, [diag]);
        };
        if (document.notNil and: { uri != document.quuid }) {
            this.clearDiagnostics(document.quuid);
        };
    }

    clearDiagnostics {
        |uri|
        this.sendDiagnostics(uri, []);
    }

    sendDiagnostics {
        |uri, diagnostics|
        server.prSendMessage((
            method: "textDocument/publishDiagnostics",
            params: (uri: uri, diagnostics: diagnostics)
        ))
    }

    onReceived {
        |method, params|
        var source, document, function, guestUser, result, deferredResult,
            documentEnvironment, savedEnvironment, startLine, startCol;

        source = params["sourceCode"];
        guestUser = params["user"];
        document = LSPDocument.findByQUuid(params["textDocument"]["uri"].urlDecode);

        deferredResult = Deferred();

        startLine = 0;
        startCol = 0;
        params["range"] !? { |range|
            range["start"] !? { |start|
                startLine = start["line"].asInteger;
                startCol = start["character"].asInteger;
            };
        };

        thisProcess.interpreter.preProcessor !? { |pre| pre.value(source, thisProcess.interpreter) };
        // Compile with the document path and selection offset so error
        // locations in the report reference the real file.
        function = document.path !? {
            thisProcess.interpreter.compile(source, document.path.asSymbol, startLine, startCol)
        } ?? {
            source.compile()
        };

        this.postBeforeEvaluate.value.postln;

        if (function.isNil) {
            deferredResult.value = (compileError: "Compile error?");
        } {
            thisProcess.nowExecutingPath = document.path;
            Document.current = document;

            documentEnvironment = document.envir;
            savedEnvironment = currentEnvironment;
            currentEnvironment = documentEnvironment ?? {
                this.class.evaluateEnvironment ?? { currentEnvironment ?? { topEnvironment } }
            };

            try {
                result = this.doEvaluate(function);

                result = String.streamContentsLimit({
                    |stream|
                    result.printOn(stream);
                }, resultStringLimit);

                if (resultStringLimit.size >= resultStringLimit, { ^(result ++ "...etc..."); });
                if (postResult) {
                    if (guestUser.notNil) {
                        guestUserPrefix.format(guestUser).post;
                    } {
                        resultPrefix.post;
                    };

                    result.postln;
                };
                deferredResult.value = (result: result);
                this.clearDiagnostics(document.quuid);
            } {
                |error|
                var report = this.captureErrorReport(error);
                if (postResult) {
                    report.post;
                };
                server.prSendMessage((
                    method: "window/logMessage",
                    params: (type: 1, message: report)
                ));
                this.publishErrorDiagnostics(report, error, document);
                deferredResult.value = (error: report);
            };

            if (documentEnvironment.isNil) {
                this.class.evaluateEnvironment = currentEnvironment;
            };
            currentEnvironment = savedEnvironment;

            thisProcess.nowExecutingPath = nil;
        };

        this.postAfterEvaluate.value.postln;

        ^deferredResult
    }
}
