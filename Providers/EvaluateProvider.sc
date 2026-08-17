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

    onReceived {
        |method, params|
        var source, document, function, guestUser, result, deferredResult,
            documentEnvironment, savedEnvironment;
        
        source = params["sourceCode"];
        guestUser = params["user"];
        document = LSPDocument.findByQUuid(params["textDocument"]["uri"].urlDecode);
        
        deferredResult = Deferred();
        
        thisProcess.interpreter.preProcessor !? { |pre| pre.value(source, thisProcess.interpreter) };
        function = source.compile();
        
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

