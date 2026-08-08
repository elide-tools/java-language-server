package org.javacs.lens;

import org.javacs.ParseTask;
import org.javacs.lsp.CodeLens;

import java.util.ArrayList;
import java.util.List;

public class CodeLensProvider {

    public static List<CodeLens> find(ParseTask task) {
        var list = new ArrayList<CodeLens>();
        new FindCodeLenses(task.task).scan(task.root, list);
        return list;
    }
}
