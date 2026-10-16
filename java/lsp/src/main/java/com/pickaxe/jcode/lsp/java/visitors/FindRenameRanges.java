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

package com.pickaxe.jcode.lsp.java.visitors;

import com.pickaxe.jcode.lsp.java.compiler.CompileTask;
import com.pickaxe.jcode.lsp.java.utils.FindHelper;
import com.pickaxe.jcode.models.Location;
import java.util.List;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.Name;
import openjdk.source.tree.ClassTree;
import openjdk.source.tree.ExpressionTree;
import openjdk.source.tree.IdentifierTree;
import openjdk.source.tree.MemberReferenceTree;
import openjdk.source.tree.MemberSelectTree;
import openjdk.source.tree.MethodTree;
import openjdk.source.tree.NewClassTree;
import openjdk.source.tree.VariableTree;
import openjdk.source.util.TreePath;
import openjdk.source.util.TreePathScanner;
import openjdk.source.util.Trees;

/**
 * Collects the ranges of an element's declaration and all of its references, so that the element
 * can be renamed. Unlike {@link FindReferences}, this visitor emits the ranges of the identifiers
 * themselves (not the whole expression) and includes declarations.
 *
 * @author Akash Yadav
 */
public class FindRenameRanges extends TreePathScanner<Void, List<Location>> {

  private final CompileTask task;
  private final Element find;
  private ClassTree surroundingClass;

  public FindRenameRanges(CompileTask task, Element find) {
    this.task = task;
    this.find = find;
  }

  @Override
  public Void visitClass(ClassTree t, List<Location> list) {
    ClassTree push = surroundingClass;
    surroundingClass = t;
    final Name name = t.getSimpleName();
    if (name.length() > 0 && check()) {
      list.add(FindHelper.location(task, getCurrentPath(), name));
    }
    super.visitClass(t, list);
    surroundingClass = push;
    return null;
  }

  @Override
  public Void visitMethod(MethodTree t, List<Location> list) {
    Name name = t.getName();
    if (name.contentEquals("<init>")) {
      name = surroundingClass.getSimpleName();
    }
    if (check()) {
      list.add(FindHelper.location(task, getCurrentPath(), name));
    }
    return super.visitMethod(t, list);
  }

  @Override
  public Void visitVariable(VariableTree t, List<Location> list) {
    if (check()) {
      list.add(FindHelper.location(task, getCurrentPath(), t.getName()));
    }
    return super.visitVariable(t, list);
  }

  @Override
  public Void visitNewClass(NewClassTree t, List<Location> list) {
    if (check()) {
      final String name = newClassIdentifier(t);
      if (!name.isEmpty()) {
        list.add(FindHelper.location(task, getCurrentPath(), name));
      }
    }
    return super.visitNewClass(t, list);
  }

  private String newClassIdentifier(NewClassTree t) {
    final ExpressionTree identifier = t.getIdentifier();
    if (identifier instanceof IdentifierTree) {
      return ((IdentifierTree) identifier).getName().toString();
    }
    if (identifier instanceof MemberSelectTree) {
      return ((MemberSelectTree) identifier).getIdentifier().toString();
    }
    return identifier.toString();
  }

  @Override
  public Void visitMemberSelect(MemberSelectTree t, List<Location> list) {
    if (check()) {
      list.add(FindHelper.location(task, getCurrentPath(), t.getIdentifier()));
    }
    return super.visitMemberSelect(t, list);
  }

  @Override
  public Void visitMemberReference(MemberReferenceTree t, List<Location> list) {
    if (check()) {
      list.add(FindHelper.location(task, getCurrentPath(), t.getName()));
    }
    return super.visitMemberReference(t, list);
  }

  @Override
  public Void visitIdentifier(IdentifierTree t, List<Location> list) {
    if (check()) {
      list.add(FindHelper.location(task, getCurrentPath(), t.getName()));
    }
    return super.visitIdentifier(t, list);
  }

  private boolean check() {
    final Element candidate = Trees.instance(task.task).getElement(getCurrentPath());
    return find.equals(candidate);
  }
}
