/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.lsp.java.providers;

import androidx.annotation.NonNull;
import com.pickaxe.jcode.lsp.java.compiler.CompileTask;
import com.pickaxe.jcode.lsp.java.compiler.CompilerProvider;
import com.pickaxe.jcode.lsp.java.compiler.SynchronizedTask;
import com.pickaxe.jcode.lsp.java.utils.CancelChecker;
import com.pickaxe.jcode.lsp.java.utils.NavigationHelper;
import com.pickaxe.jcode.lsp.java.visitors.FindRenameRanges;
import com.pickaxe.jcode.lsp.models.DocumentChange;
import com.pickaxe.jcode.lsp.models.RenameParams;
import com.pickaxe.jcode.lsp.models.RenameResult;
import com.pickaxe.jcode.lsp.models.TextEdit;
import com.pickaxe.jcode.models.Location;
import com.pickaxe.jcode.progress.ICancelChecker;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.TypeElement;
import openjdk.source.tree.CompilationUnitTree;

/**
 * Provides rename support for Java sources.
 *
 * <p>This is a reference based implementation: the symbol at the requested position is resolved,
 * all of its references are collected and each of them is replaced with the new name. Renaming
 * types (classes, interfaces, enums, annotations) and constructors is not supported, because it
 * would require renaming the file and updating all of its imports.
 *
 * @author Akash Yadav
 */
public class RenameProvider extends CancelableServiceProvider {

  public static final List<Location> NOT_SUPPORTED = Collections.emptyList();
  private final CompilerProvider compiler;
  private Path file;
  private int line, column;
  private String newName;

  public RenameProvider(CompilerProvider compiler, ICancelChecker checker) {
    super(checker);
    this.compiler = compiler;
  }

  @NonNull
  public RenameResult rename(@NonNull RenameParams params) {
    this.file = params.getFile();

    // 1-based line and column indexes
    this.line = params.getPosition().getLine() + 1;
    this.column = params.getPosition().getColumn() + 1;
    this.newName = params.getNewName();

    List<Location> locations;
    try {
      locations = find();
    } catch (Exception err) {
      if (!CancelChecker.isCancelled(err)) {
        throw err;
      }
      locations = new ArrayList<>();
    }

    return toResult(locations);
  }

  private RenameResult toResult(List<Location> locations) {
    final Map<Path, List<TextEdit>> perFile = new LinkedHashMap<>();
    for (Location location : locations) {
      perFile
          .computeIfAbsent(location.getFile(), key -> new ArrayList<>())
          .add(new TextEdit(location.getRange(), newName));
    }

    final List<DocumentChange> changes = new ArrayList<>();
    perFile.forEach((path, edits) -> changes.add(new DocumentChange(path, edits)));
    return new RenameResult(changes);
  }

  public List<Location> find() {
    abortIfCancelled();
    final SynchronizedTask synchronizedTask = compiler.compile(file);

    // findMemberReferences initiates another compilation task. However, initiating a compilation
    // task while another compilation is in progress will result in a deadlock. Therefore, we return
    // a supplier from the current synchronized task and compute the locations later.
    final Supplier<List<Location>> result =
        synchronizedTask.get(
            task -> {
              abortIfCancelled();
              final Element element =
                  NavigationHelper.findElement(task, file, line, column, this);
              if (element == null) {
                return () -> NOT_SUPPORTED;
              }

              // Types and constructors are renamed by renaming the type, which would require
              // renaming the file as well. Not supported here.
              if (NavigationHelper.isType(element)
                  || element.getKind() == ElementKind.CONSTRUCTOR) {
                return () -> NOT_SUPPORTED;
              }

              if (NavigationHelper.isLocal(element)) {
                final var locations = findRenameRanges(task);
                return () -> locations;
              }

              if (NavigationHelper.isMember(element)) {
                final var parentClass = (TypeElement) element.getEnclosingElement();
                final var className = parentClass.getQualifiedName().toString();
                final String memberName = element.getSimpleName().toString();
                return () -> findMemberRanges(className, memberName);
              }

              return () -> NOT_SUPPORTED;
            });

    return result.get();
  }

  private List<Location> findMemberRanges(String className, String memberName) {
    abortIfCancelled();
    final var files = compiler.findMemberReferences(className, memberName);
    if (files.length == 0) {
      return Collections.emptyList();
    }

    abortIfCancelled();
    return compiler.compile(files).get(this::findRenameRanges);
  }

  private List<Location> findRenameRanges(CompileTask task) {
    abortIfCancelled();
    final Element element = NavigationHelper.findElement(task, file, line, column, this);
    final List<Location> locations = new ArrayList<>();
    if (element == null) {
      return locations;
    }

    for (CompilationUnitTree root : task.roots) {
      abortIfCancelled();
      new FindRenameRanges(task, element).scan(root, locations);
    }
    return locations;
  }
}
