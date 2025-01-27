/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.javascript.jscomp.CodingConvention.SubclassRelationship;
import com.google.javascript.jscomp.ReferenceCollector.Behavior;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Using the infrastructure provided by VariableReferencePass, identify variables that are used only
 * once and in a way that is safe to move, and then inline them.
 *
 * <p>This pass has two "modes." One mode only inlines variables declared as constants, for legacy
 * compiler clients. The second mode inlines any variable that we can provably inline. Note that the
 * second mode is a superset of the first mode. We only support the first mode for
 * backwards-compatibility with compiler clients that don't want --inline_variables.
 *
 * <p>The approach of this pass is similar to {@link CrossChunkCodeMotion}
 */
class InlineVariables implements CompilerPass {

  private final AbstractCompiler compiler;

  enum Mode {
    // Only inline things explicitly marked as constant.
    CONSTANTS_ONLY(Var::isDeclaredOrInferredConst),
    // Locals only
    LOCALS_ONLY(Var::isLocal),
    ALL(Predicates.alwaysTrue());

    @SuppressWarnings("ImmutableEnumChecker")
    private final Predicate<Var> varPredicate;

    private Mode(Predicate<Var> varPredicate) {
      this.varPredicate = varPredicate;
    }
  }

  private final Mode mode;

  // Inlines all strings, even if they increase the size of the gzipped binary.
  private final boolean inlineAllStrings;

  InlineVariables(
      AbstractCompiler compiler,
      Mode mode,
      boolean inlineAllStrings) {
    this.compiler = compiler;
    this.mode = mode;
    this.inlineAllStrings = inlineAllStrings;
  }

  @Override
  public void process(Node externs, Node root) {
    ReferenceCollector callback =
        new ReferenceCollector(
            compiler,
            new InliningBehavior(),
            new SyntacticScopeCreator(compiler),
            mode.varPredicate);
    callback.process(externs, root);
  }

  private static class AliasCandidate {
    private final Var alias;
    private final ReferenceCollection refInfo;

    AliasCandidate(Var alias, ReferenceCollection refInfo) {
      this.alias = alias;
      this.refInfo = refInfo;
    }
  }

  /**
   * Builds up information about nodes in each scope. When exiting the
   * scope, inspects all variables in that scope, and inlines any
   * that we can.
   */
  private class InliningBehavior implements Behavior {

    /**
     * A list of variables that should not be inlined, because their
     * reference information is out of sync with the state of the AST.
     */
    private final Set<Var> staleVars = new HashSet<>();

    /**
     * Stored possible aliases of variables that never change, with
     * all the reference info about those variables. Hashed by the NAME
     * node of the variable being aliased.
     */
    final Map<Node, AliasCandidate> aliasCandidates = new HashMap<>();

    @Override
    public void afterExitScope(NodeTraversal t, ReferenceMap referenceMap) {
      collectAliasCandidates(t, referenceMap);
      doInlinesForScope(t, referenceMap);
    }

    /**
     * If any of the variables are well-defined and alias other variables,
     * mark them as aliasing candidates.
     */
    private void collectAliasCandidates(NodeTraversal t,
        ReferenceMap referenceMap) {
      if (mode != Mode.CONSTANTS_ONLY) {
        for (Var v : t.getScope().getVarIterable()) {
          ReferenceCollection referenceInfo = referenceMap.getReferences(v);

          // NOTE(nicksantos): Don't handle variables that are never used.
          // The tests are much easier to write if you don't, and there's
          // another pass that handles unused variables much more elegantly.
          if (referenceInfo != null && referenceInfo.references.size() >= 2 &&
              referenceInfo.isWellDefined() &&
              referenceInfo.isAssignedOnceInLifetime()) {
            Reference init = referenceInfo.getInitializingReference();
            Node value = init.getAssignedValue();
            if (value != null && value.isName() && !value.getString().equals(v.getName())) {
              aliasCandidates.put(value, new AliasCandidate(v, referenceInfo));
            }
          }
        }
      }
    }

    /**
     * For all variables in this scope, see if they are only used once.
     * If it looks safe to do so, inline them.
     */
    private void doInlinesForScope(NodeTraversal t, ReferenceMap referenceMap) {
      boolean maybeModifiedArguments =
          maybeEscapedOrModifiedArguments(t.getScope(), referenceMap);
      for (Var v : t.getScope().getVarIterable()) {
        ReferenceCollection referenceInfo = referenceMap.getReferences(v);

        // referenceInfo will be null if we're in constants-only mode
        // and the variable is not a constant.
        if (referenceInfo == null
            || isVarInlineForbidden(v)
            || valueUsesGoogLocale(referenceInfo)) {
          // Never try to inline exported variables or variables that
          // were not collected or variables that have already been inlined.
          // Also, don't inline values that include `goog$LOCALE`, because that would force us
          // to mark it as stale and prevent us from inlining it in this pass run.
          // It's important that we inline `goog$LOCALE` ASAP to reduce the number of optimization
          // loop iterations required to eliminate unused locale-specific code.
          // TODO(b/225049652): In general favor inlining outer scope variables first.
          continue;
        } else if (isInlineableDeclaredConstant(v, referenceInfo)) {
          Reference init = referenceInfo.getInitializingReferenceForConstants();
          Node value = init.getAssignedValue();
          inlineWellDefinedVariable(v, value, referenceInfo.references);
          staleVars.add(v);
        } else if (mode == Mode.CONSTANTS_ONLY) {
          // If we're in constants-only mode, don't run more aggressive
          // inlining heuristics. See InlineConstantsTest.
          continue;
        } else {
          inlineNonConstants(v, referenceInfo, maybeModifiedArguments);
        }
      }
    }

    private boolean valueUsesGoogLocale(ReferenceCollection referenceInfo) {
      // getInitializingReferenceForConstants() is the most robust way of getting the initializing
      // reference.
      Reference initRef = referenceInfo.getInitializingReferenceForConstants();
      if (initRef != null) {
        Node value = initRef.getAssignedValue();
        return value != null && containsGoogLocale(value);
      }
      return false;
    }

    private boolean containsGoogLocale(Node value) {
      if (value.isName()) {
        return isGoogLocaleNameString(value.getString());
      } else {
        for (Node c = value.getFirstChild(); c != null; c = c.getNext()) {
          if (containsGoogLocale(c)) {
            return true;
          }
        }
        return false;
      }
    }

    private boolean maybeEscapedOrModifiedArguments(Scope scope, ReferenceMap referenceMap) {
      if (scope.isFunctionScope() && !scope.getRootNode().isArrowFunction()) {
        Var arguments = scope.getArgumentsVar();
        ReferenceCollection refs = referenceMap.getReferences(arguments);
        if (refs != null && !refs.references.isEmpty()) {
          for (Reference ref : refs.references) {
            Node refNode = ref.getNode();
            Node refParent = ref.getParent();
            // Any reference that is not a read of the arguments property
            // consider a escape of the arguments object.
            if (!(NodeUtil.isNormalGet(refParent)
                && refNode == ref.getParent().getFirstChild()
                && !NodeUtil.isLValue(refParent))) {
              return true;
            }
          }
        }
      }
      return false;
    }

    private boolean isGoogLocaleVar(Var v) {
      return v != null && isGoogLocaleNameString(v.getName());
    }

    private boolean isGoogLocaleNameString(String s) {
      return s.equals("goog$LOCALE");
    }

    private void inlineNonConstants(
        Var v, ReferenceCollection referenceInfo,
        boolean maybeModifiedArguments) {
      int refCount = referenceInfo.references.size();
      Reference declaration = referenceInfo.references.get(0);
      Reference init = referenceInfo.getInitializingReference();
      int firstRefAfterInit = (declaration == init) ? 2 : 3;
      if (refCount > 1 &&
          isImmutableAndWellDefinedVariable(v, referenceInfo)) {
        // if the variable is referenced more than once, we can only
        // inline it if it's immutable and never defined before referenced.
        Node value;
        if (init != null) {
          value = init.getAssignedValue();
        } else {
          // Create a new node for variable that is never initialized.
          Node srcLocation = declaration.getNode();
          value = NodeUtil.newUndefinedNode(srcLocation);
        }
        checkNotNull(value);
        inlineWellDefinedVariable(v, value, referenceInfo.references);
        staleVars.add(v);
      } else if (refCount == firstRefAfterInit) {
        // The variable likely only read once, try some more
        // complex inlining heuristics.
        Reference reference = referenceInfo.references.get(firstRefAfterInit - 1);
        if (canInline(declaration, init, reference)) {
          inline(v, declaration, init, reference);
          staleVars.add(v);
        }
      } else if (declaration != init && refCount == 2) {
        if (isValidDeclaration(declaration) && isValidInitialization(init)) {
          // The only reference is the initialization, remove the assignment and
          // the variable declaration.
          Node value = init.getAssignedValue();
          checkNotNull(value);
          inlineWellDefinedVariable(v, value, referenceInfo.references);
          staleVars.add(v);
        }
      }

      // If this variable was not inlined normally, check if we can
      // inline an alias of it. (If the variable was inlined, then the
      // reference data is out of sync. We're better off just waiting for
      // the next pass.)
      if (!maybeModifiedArguments
          && !staleVars.contains(v)
          && referenceInfo.isWellDefined()
          && referenceInfo.isAssignedOnceInLifetime()) {
        List<Reference> refs = referenceInfo.references;
        for (int i = 1 /* start from a read */; i < refs.size(); i++) {
          Node nameNode = refs.get(i).getNode();

          AliasCandidate candidate = aliasCandidates.get(nameNode);
          if (candidate == null
              || staleVars.contains(candidate.alias)
              || isVarInlineForbidden(candidate.alias)) {
            continue;
          }

          Reference aliasInit = candidate.refInfo.getInitializingReference();
          Node value = checkNotNull(aliasInit.getAssignedValue());
          inlineWellDefinedVariable(candidate.alias, value, candidate.refInfo.references);
          staleVars.add(candidate.alias);
        }
      }
    }

    /**
     * If there are any variable references in the given node tree, skiplist them to prevent the
     * pass from trying to inline the variable.
     */
    private void recordStaleVarReferencesInTree(Node root, Scope scope) {
      for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
        recordStaleVarReferencesInTree(c, scope);
      }

      if (root.isName()) {
        staleVars.add(scope.getVar(root.getString()));
      }
    }

    /**
     * Whether the given variable is forbidden from being inlined.
     */
    private boolean isVarInlineForbidden(Var var) {
      // A variable may not be inlined if:
      // 1) The variable is exported,
      // 2) A reference to the variable has been inlined. We're downstream
      //    of the mechanism that creates variable references, so we don't
      //    have a good way to update the reference. Just punt on it.
      // 3) Don't inline the special property rename functions.
      return var.isExtern()
          || compiler.getCodingConvention().isExported(var.getName(), /* local */ var.isLocal())
          || compiler.getCodingConvention().isPropertyRenameFunction(var.getNameNode())
          || staleVars.contains(var)
          || hasNoInlineAnnotation(var);
    }

    /**
     * Do the actual work of inlining a single declaration into a single
     * reference.
     */
    private void inline(Var v, Reference decl, Reference init, Reference ref) {
      Node value = init.getAssignedValue();
      checkState(value != null);
      // Check for function declarations before the value is moved in the AST.
      boolean isFunctionDeclaration = NodeUtil.isFunctionDeclaration(value);
      if (isFunctionDeclaration) {
        // In addition to changing the containing scope, inlining function declarations also changes
        // the function name scope from the containing scope to the inner scope.
        compiler.reportChangeToChangeScope(value);
        compiler.reportChangeToEnclosingScope(value.getParent());
      }
      inlineValue(v.getScope(), ref.getNode(), value.detach());
      if (decl != init) {
        Node expressRoot = init.getGrandparent();
        checkState(expressRoot.isExprResult());
        NodeUtil.removeChild(expressRoot.getParent(), expressRoot);
      }
      // Function declarations have already been removed.
      if (!isFunctionDeclaration) {
        removeDeclaration(decl);
      }
    }

    /** Inline an immutable variable into all of its references. */
    private void inlineWellDefinedVariable(Var v, Node value, List<Reference> refSet) {
      Scope scope = v.getScope();
      for (Reference r : refSet) {
        if (r.getNode() == v.getNameNode()) {
          removeDeclaration(r);
        } else if (r.isSimpleAssignmentToName()) {
          /**
           * This is the initialization.
           *
           * <p>Replace the entire assignment with just the value, and use the original value node
           * in case it contains references to variables that still require inlining.
           */
          inlineValue(scope, r.getParent(), value.detach());
        } else {
          Node clonedValue = value.cloneTree();
          NodeUtil.markNewScopesChanged(clonedValue, compiler);
          inlineValue(scope, r.getNode(), clonedValue);
        }
      }
    }

    /**
     * Remove the given VAR declaration.
     */
    private void removeDeclaration(Reference decl) {
      Node varNode = decl.getParent();
      checkState(NodeUtil.isNameDeclaration(varNode), varNode);
      Node grandparent = decl.getGrandparent();

      compiler.reportChangeToEnclosingScope(decl.getNode());
      decl.getNode().detach();
      // Remove var node if empty
      if (!varNode.hasChildren()) {
        NodeUtil.removeChild(grandparent, varNode);
      }
    }

    private void inlineValue(Scope scope, Node toRemove, Node toInsert) {
      compiler.reportChangeToEnclosingScope(toRemove);

      // Help type-based optimizations by propagating more specific types from type assertions
      if (toRemove.getColor() != null && toRemove.isColorFromTypeCast()) {
        toInsert.setColor(toRemove.getColor());
        toInsert.setColorFromTypeCast();
      }
      toRemove.replaceWith(toInsert);
      NodeUtil.markFunctionsDeleted(toRemove, compiler);

      recordStaleVarReferencesInTree(toInsert, scope);
    }

    /** Determines whether the given variable is declared as a constant and may be inlined. */
    private boolean isInlineableDeclaredConstant(Var var, ReferenceCollection refInfo) {
      if (!Mode.CONSTANTS_ONLY.varPredicate.apply(var)) {
        return false;
      }

      if (!refInfo.isAssignedOnceInLifetime()) {
        return false;
      }

      Reference init = refInfo.getInitializingReferenceForConstants();
      if (init == null) {
        return false;
      }

      Node value = init.getAssignedValue();
      if (value == null) {
        // This constant is either externally defined or initialized indirectly
        // (e.g. in an function expression used to hide
        // temporary variables), so the constant is ineligible for inlining.
        return false;
      }

      // Is the constant's value immutable?
      if (!NodeUtil.isImmutableValue(value)) {
        return false;
      }

      // Determine if we should really inline a String or not.
      return !value.isStringLit() || isStringWorthInlining(var, refInfo.references);
    }

    /**
     * Compute whether the given string is worth inlining.
     */
    private boolean isStringWorthInlining(Var var, List<Reference> refs) {
      if (isGoogLocaleVar(var)) {
        // Once we have a literal string value for it, we always want to inline `goog.LOCALE`,
        // because doing so lets us rip out lots of unused locale-specific code.
        return true;
      }
      if (!inlineAllStrings && !var.isDefine()) {
        int len = var.getInitialValue().getString().length() + "''".length();

        // if not inlined: var xx="value"; .. xx .. xx ..
        // The 4 bytes per reference is just a heuristic:
        // 2 bytes per var name plus maybe 2 bytes if we don't inline, e.g.
        // in the case of "foo " + CONST + " bar"
        int noInlineBytes = "var xx=;".length() + len +
                            4 * (refs.size() - 1);

        // if inlined:
        // I'm going to assume that half of the quotes will be eliminated
        // thanks to constant folding, therefore I subtract 1 (2/2=1) from
        // the string length.
        int inlineBytes = (len - 1) * (refs.size() - 1);

        // Not inlining if doing so uses more bytes, or this constant is being
        // defined.
        return noInlineBytes >= inlineBytes;
      }

      return true;
    }

    /**
     * @return true if the provided reference and declaration can be safely
     *         inlined according to our criteria
     */
    private boolean canInline(
        Reference declaration,
        Reference initialization,
        Reference reference) {
      if (!isValidDeclaration(declaration)
          || !isValidInitialization(initialization)
          || !isValidReference(reference)) {
        return false;
      }

      // If the value is read more than once, skip it.
      // VAR declarations and EXPR_RESULT don't need the value, but other
      // ASSIGN expressions parents do.
      if (declaration != initialization &&
          !initialization.getGrandparent().isExprResult()) {
        return false;
      }

      // Be very conservative and do not cross control structures or scope boundaries
      if (declaration.getBasicBlock() != initialization.getBasicBlock()
          || declaration.getBasicBlock() != reference.getBasicBlock()) {
        return false;
      }

      // Do not inline into a call node. This would change
      // the context in which it was being called. For example,
      //   var a = b.c;
      //   a();
      // should not be inlined, because it calls a in the context of b
      // rather than the context of the window.
      //   var a = b.c;
      //   f(a)
      // is OK.
      Node value = initialization.getAssignedValue();
      checkState(value != null);
      if (value.isGetProp()
          && reference.getParent().isCall()
          && reference.getParent().getFirstChild() == reference.getNode()) {
        return false;
      }

      if (value.isFunction()) {
        Node callNode = reference.getParent();
        if (reference.getParent().isCall()) {
          CodingConvention convention = compiler.getCodingConvention();
          // Bug 2388531: Don't inline subclass definitions into class defining
          // calls as this confused class removing logic.
          SubclassRelationship relationship =
              convention.getClassesDefinedByCall(callNode);
          if (relationship != null) {
            return false;
          }

          // issue 668: Don't inline singleton getter methods
          // calls as this confused class removing logic.
          if (convention.getSingletonGetterClassName(callNode) != null) {
            return false;
          }
        }
      }

      if (initialization.getScope() != declaration.getScope()
          || !initialization.getScope().contains(reference.getScope())) {
        return false;
      }

      return canMoveAggressively(value) || canMoveModerately(initialization, reference);
    }

    /**
     * If the value is a literal, we can cross more boundaries to inline it.
     */
    private boolean canMoveAggressively(Node value) {
      // Function expressions and other mutable objects can move within
      // the same basic block.
      return NodeUtil.isLiteralValue(value, true)
          || value.isFunction();
    }

    /**
     * If the value of a variable is not constant, then it may read or modify
     * state. Therefore it cannot be moved past anything else that may modify
     * the value being read or read values that are modified.
     */
    private boolean canMoveModerately(
        Reference initialization,
        Reference reference) {
      // Check if declaration can be inlined without passing
      // any side-effect causing nodes.
      Iterator<Node> it;
      if (NodeUtil.isNameDeclaration(initialization.getParent())) {
        it =
            NodeIterators.LocalVarMotion.forVar(
                compiler,
                initialization.getNode(), // NAME
                initialization.getParent(), // VAR/LET/CONST
                initialization.getGrandparent()); // VAR/LET/CONST container
      } else if (initialization.getParent().isAssign()) {
        checkState(initialization.getGrandparent().isExprResult());
        it =
            NodeIterators.LocalVarMotion.forAssign(
                compiler,
                initialization.getNode(), // NAME
                initialization.getParent(), // ASSIGN
                initialization.getGrandparent(), // EXPR_RESULT
                initialization.getGrandparent().getParent()); // EXPR container
      } else {
        throw new IllegalStateException("Unexpected initialization parent\n"
            + initialization.getParent().toStringTree());
      }
      Node targetName = reference.getNode();
      while (it.hasNext()) {
        Node curNode = it.next();
        if (curNode == targetName) {
          return true;
        }
      }

      return false;
    }

    /**
     * @return true if the reference is a normal VAR or FUNCTION declaration.
     */
    private boolean isValidDeclaration(Reference declaration) {
      return (NodeUtil.isNameDeclaration(declaration.getParent())
              && !NodeUtil.isLoopStructure(declaration.getGrandparent()))
          || NodeUtil.isFunctionDeclaration(declaration.getParent());
    }

    /**
     * @return Whether there is a initial value.
     */
    private boolean isValidInitialization(Reference initialization) {
      if (initialization == null) {
        return false;
      } else if (initialization.isDeclaration()) {
        // The reference is a FUNCTION declaration or normal VAR declaration
        // with a value.
        if (!NodeUtil.isFunctionDeclaration(initialization.getParent())
            && !initialization.getNode().hasChildren()) {
          return false;
        }
      } else {
        Node parent = initialization.getParent();
        checkState(parent.isAssign() && parent.getFirstChild() == initialization.getNode());
      }

      Node n = initialization.getAssignedValue();
      if (n.isFunction()) {
        return compiler.getCodingConvention().isInlinableFunction(n);
      }

      return true;
    }

    /**
     * @return true if the reference is a candidate for inlining
     */
    private boolean isValidReference(Reference reference) {
      return !reference.isDeclaration() && !reference.isLvalue();
    }

    /**
     * Determines whether the reference collection describes a variable that
     * is initialized to an immutable value, never modified, and defined before
     * every reference.
     */
    private boolean isImmutableAndWellDefinedVariable(Var v,
        ReferenceCollection refInfo) {
      List<Reference> refSet = refInfo.references;
      int startingReadRef = 1;
      Reference refDecl = refSet.get(0);
      if (!isValidDeclaration(refDecl)) {
        return false;
      }

      boolean isNeverAssigned = refInfo.isNeverAssigned();
      // For values that are never assigned, only the references need to be
      // checked.
      if (!isNeverAssigned) {
        Reference refInit = refInfo.getInitializingReference();
        if (!isValidInitialization(refInit)) {
          return false;
        }

        if (refDecl != refInit) {
          checkState(refInit == refSet.get(1));
          startingReadRef = 2;
        }

        if (!refInfo.isWellDefined()) {
          return false;
        }

        Node value = refInit.getAssignedValue();
        checkNotNull(value);

        boolean isImmutableValueWorthInlining =
            NodeUtil.isImmutableValue(value)
                && (!value.isStringLit() || isStringWorthInlining(v, refInfo.references));
        boolean isInlinableThisAlias = value.isThis() && !refInfo.isEscaped();
        if (!isImmutableValueWorthInlining && !isInlinableThisAlias) {
          return false;
        }
      }

      for (int i = startingReadRef; i < refSet.size(); i++) {
        Reference ref = refSet.get(i);
        if (!isValidReference(ref)) {
          return false;
        }
      }
      return true;
    }
  }

  private static boolean hasNoInlineAnnotation(Var var) {
    JSDocInfo jsDocInfo = var.getJSDocInfo();
    return jsDocInfo != null && jsDocInfo.isNoInline();
  }
}
