package algorithms;

/**
 * 盛最多水的容器 (Container With Most Water)
 *
 * 题目：给定数组 height，每个元素代表柱子高度，宽度为 1。
 * 选两根柱子和 x 轴构成容器，求最大盛水量（面积）。
 *
 * 核心思路：双指针 + 贪心
 * 1. 左右指针分别指向数组两端（此时宽度最大）
 * 2. 面积 = 短板高度 × 两指针距离
 * 3. 移动较矮的指针向中间靠拢：
 *    —— 因为宽度一定减小，移动长板不可能增加高度（短板不变），面积必然变小
 *    —— 只有移动短板，才可能遇到更高的柱子，用高度弥补宽度
 * 4. 重复直到两指针相遇，过程中记录最大面积
 *
 * 时间复杂度 O(n)，空间复杂度 O(1)
 */
public class MaxArea {

    public static int getMaxArea(int[] nums) {
        Integer start = 0;           // 左指针
        Integer end = nums.length - 1; // 右指针

        int max = 0; // 记录最大面积

        while (!start.equals(end)) {
            // 当前容器高度由短板决定
            int high = Integer.min(nums[start], nums[end]);
            // 当前面积 = 高度 × 宽度
            int cur = Integer.max(max, high * (end - start));
            if (cur > max) {
                max = cur;
            }
            // 移动较矮一侧的指针（瓶颈在矮板，改变它才有机会变大）
            if (nums[start] > nums[end]) {
                end--;
            } else {
                start++;
            }
        }
        return max;
    }

    public static void main(String[] args) {
        int[] nums = {1, 8, 6, 2, 5, 4, 8, 3, 7};
        int maxArea = getMaxArea(nums);
        System.out.println(maxArea); // 49
    }
}
