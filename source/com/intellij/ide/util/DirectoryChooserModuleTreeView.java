package com.intellij.ide.util;

import com.intellij.ide.IconUtilEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Iconable;
import com.intellij.psi.PsiDirectory;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.util.containers.HashMap;
import com.intellij.util.ui.Tree;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import java.util.*;

/**
 * @author dsl
 */
public class DirectoryChooserModuleTreeView implements DirectoryChooserView {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.util.DirectoryChooserModuleTreeView");

  private final Tree myTree;
  private final List<DirectoryChooser.ItemWrapper>  myItems = new ArrayList<DirectoryChooser.ItemWrapper>();
  private final Map<DirectoryChooser.ItemWrapper, DefaultMutableTreeNode> myItemNodes = new HashMap<DirectoryChooser.ItemWrapper, DefaultMutableTreeNode>();
  private final Map<Module, DefaultMutableTreeNode> myModuleNodes = new HashMap<Module, DefaultMutableTreeNode>();
  private final DefaultMutableTreeNode myRootNode;
  private ProjectFileIndex myFileIndex;

  public DirectoryChooserModuleTreeView(Project project) {
    myRootNode = new DefaultMutableTreeNode();
    myTree = new Tree(myRootNode);
    myTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    myFileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    myTree.setCellRenderer(new MyTreeCellRenderer());
    new TreeSpeedSearch(myTree) {
      public boolean isMatchingElement(Object element, String pattern) {
        if (element instanceof TreePath) {
          final Object userObject = ((DefaultMutableTreeNode)((TreePath)element).getLastPathComponent()).getUserObject();
          if (userObject instanceof Module) {
            return ((Module)userObject).getName().toLowerCase().startsWith(pattern.toLowerCase());
          }
        }
        return false;
      }
    };
  }

  public void clearItems() {
    myRootNode.removeAllChildren();
    myItems.clear();
    myItemNodes.clear();
    myModuleNodes.clear();
  }

  public JComponent getComponent() {
    return myTree;
  }

  public void onSelectionChange(final Runnable runnable) {
    myTree.getSelectionModel().addTreeSelectionListener(new TreeSelectionListener() {
      public void valueChanged(TreeSelectionEvent e) {
        runnable.run();
      }
    });
  }

  public DirectoryChooser.ItemWrapper getItemByIndex(int i) {
    return myItems.get(i);
  }

  public void clearSelection() {
    myTree.clearSelection();
  }

  public void selectItemByIndex(int selectionIndex) {
    if (selectionIndex < 0) {
      myTree.clearSelection();
    } else {
      final DirectoryChooser.ItemWrapper itemWrapper = myItems.get(selectionIndex);
      final DefaultMutableTreeNode node = myItemNodes.get(itemWrapper);
      final TreePath treePath = expandNode(node);
      myTree.setSelectionPath(treePath);
      myTree.scrollPathToVisible(treePath);
    }
  }

  private TreePath expandNode(final DefaultMutableTreeNode node) {
    final TreeNode[] path = node.getPath();
    final TreePath treePath = new TreePath(path);
    TreePath expandPath = treePath;
    if (myTree.getModel().isLeaf(expandPath.getLastPathComponent())) {
      expandPath = expandPath.getParentPath();
    }
    myTree.expandPath(expandPath);
    return treePath;
  }

  public void addItem(DirectoryChooser.ItemWrapper itemWrapper) {
    myItems.add(itemWrapper);
    final PsiDirectory directory = itemWrapper.getDirectory();
    final Module module = myFileIndex.getModuleForFile(directory.getVirtualFile());
    DefaultMutableTreeNode node = myModuleNodes.get(module);
    if (node == null) {
      node = new DefaultMutableTreeNode(module, true);
      final Enumeration enumeration = myRootNode.children();

      final int index = Collections.binarySearch(Collections.list(enumeration), node, new Comparator<DefaultMutableTreeNode>() {
              public int compare(DefaultMutableTreeNode node1, DefaultMutableTreeNode node2) {
                return ((Module)node1.getUserObject()).getName().compareToIgnoreCase(((Module)node2.getUserObject()).getName());
              }
            });
      final int insertionPoint = -(index+1);
      LOG.assertTrue(0 <= insertionPoint && insertionPoint <= myRootNode.getChildCount());
      myRootNode.insert(node, insertionPoint);
      ((DefaultTreeModel)myTree.getModel()).nodeStructureChanged(myRootNode);
      myModuleNodes.put(module, node);
    }
    final DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(itemWrapper, false);
    myItemNodes.put(itemWrapper, itemNode);
    node.add(itemNode);
    ((DefaultTreeModel)myTree.getModel()).nodeStructureChanged(node);
  }

  public void listFilled() {
    if (myModuleNodes.size() == 1) {
      final Iterator<DefaultMutableTreeNode> iterator = myItemNodes.values().iterator();
      if (iterator.hasNext()){
        final DefaultMutableTreeNode node = iterator.next();
        expandNode(node);
      }
    }
  }

  public int getItemsSize() {
    return myItems.size();
  }

  public DirectoryChooser.ItemWrapper getSelectedItem() {
    final TreePath selectionPath = myTree.getSelectionPath();
    if (selectionPath == null) return null;
    final DefaultMutableTreeNode node = (DefaultMutableTreeNode)selectionPath.getLastPathComponent();
    return node.getUserObject() instanceof DirectoryChooser.ItemWrapper ? (DirectoryChooser.ItemWrapper)node.getUserObject() : null;
  }


  private class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    public void customizeCellRenderer(JTree tree, Object nodeValue, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
      final Object value = ((DefaultMutableTreeNode)nodeValue).getUserObject();
      if (value instanceof DirectoryChooser.ItemWrapper) {
        DirectoryChooser.ItemWrapper wrapper = (DirectoryChooser.ItemWrapper)value;
        DirectoryChooser.PathFragment[] fragments = wrapper.getFragments();
        for (int i = 0; i < fragments.length; i++) {
          DirectoryChooser.PathFragment fragment = fragments[i];
          append(fragment.getText(), fragment.isCommon() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        }
        setIcon(wrapper.getIcon(myFileIndex));
      }
      else if (value instanceof Module) {
        final Module module = (Module)value;
        append(module.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        setIcon(IconUtilEx.getIcon(module, expanded ? Iconable.ICON_FLAG_OPEN : Iconable.ICON_FLAG_CLOSED));
      }
    }
  }
}

