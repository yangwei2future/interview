package algorithms;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * 二叉树层序遍历
 * LeetCode 102 / 103
 */
public class LevelOrder {

    static class TreeNode {
        int val;
        TreeNode left, right;
        TreeNode(int val) { this.val = val; }
    }

    // ===================== 102. 层序遍历 =====================
    public static void main(String[] args) {
        /**
         *      2
         *    4    6
         *       8     10
         */

        TreeNode root = new TreeNode(2);
        root.left = new TreeNode(4);
        root.left.right = new TreeNode(8);
        root.right = new TreeNode(6);
        root.right.right = new TreeNode(10);
        List<List<Integer>> result = levelSortMethod(root);
        System.out.println(result);
    }

    static List<List<Integer>> levelSortMethod(TreeNode node){
        LinkedList<TreeNode> queue = new LinkedList<>();
        ArrayList<List<Integer>> res = new ArrayList<>();
        queue.offer(node);
        while (!queue.isEmpty()){
            ArrayList<Integer> currentLevel = new ArrayList<>();
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                TreeNode curNode = queue.poll();
                currentLevel.add(curNode.val);
                if (Objects.nonNull(curNode.left)) {
                    queue.offer(curNode.left);
                }
                if (Objects.nonNull(curNode.right)) {
                    queue.offer(curNode.right);
                }
            }
            res.add(currentLevel);
        }
        return res;
    }

}
