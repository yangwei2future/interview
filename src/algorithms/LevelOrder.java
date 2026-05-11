package algorithms;

import java.util.*;

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

    /**
     * 返回每层节点值列表
     * 思路：BFS，每层入队前记录 size，控制本层循环次数
     */
    public List<List<Integer>> levelOrder(TreeNode root) {
        List<List<Integer>> result = new ArrayList<>();
        if (root == null) return result;

        Queue<TreeNode> queue = new LinkedList<>();
        queue.offer(root);

        while (!queue.isEmpty()) {
            int size = queue.size(); // 当前层节点数
            List<Integer> level = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                TreeNode node = queue.poll();
                level.add(node.val);
                if (node.left != null)  queue.offer(node.left);
                if (node.right != null) queue.offer(node.right);
            }
            result.add(level);
        }
        return result;
    }

    // ===================== 103. 锯齿形层序遍历 =====================

    /**
     * 奇数层（1,3,5...）从左→右，偶数层从右→左
     * 思路：在层序基础上，偶数层用 addFirst 头插即可，无需反转
     */
    public List<List<Integer>> zigzagLevelOrder(TreeNode root) {
        List<List<Integer>> result = new ArrayList<>();
        if (root == null) return result;

        Queue<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        boolean leftToRight = true;

        while (!queue.isEmpty()) {
            int size = queue.size();
            LinkedList<Integer> level = new LinkedList<>();

            for (int i = 0; i < size; i++) {
                TreeNode node = queue.poll();
                if (leftToRight) {
                    level.addLast(node.val);
                } else {
                    level.addFirst(node.val); // 头插实现反向，O(1)
                }
                if (node.left != null)  queue.offer(node.left);
                if (node.right != null) queue.offer(node.right);
            }
            result.add(level);
            leftToRight = !leftToRight;
        }
        return result;
    }

    // ===================== 验证 =====================

    public static void main(String[] args) {
        LevelOrder solution = new LevelOrder();

        //        3
        //       / \
        //      9  20
        //        /  \
        //       15   7
        TreeNode root = new TreeNode(3);
        root.left  = new TreeNode(9);
        root.right = new TreeNode(20);
        root.right.left  = new TreeNode(15);
        root.right.right = new TreeNode(7);

        System.out.println("层序遍历：" + solution.levelOrder(root));
        // 期望：[[3], [9, 20], [15, 7]]

        System.out.println("锯齿遍历：" + solution.zigzagLevelOrder(root));
        // 期望：[[3], [20, 9], [15, 7]]
    }
}
