package algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 三数之和 — 排序 + 双指针 + 三层去重
 *
 * 核心思路：a + b + c = 0 → 固定 a，在右侧区间用双指针找 b + c = -a
 *
 * 排序是去重的前提：相同元素紧挨在一起，才能在循环中直接跳过
 *
 * 三层去重（几层循环，就做几层去重）：
 *   - i 层：nums[i] == nums[i-1] → 跳过，保留第一个出现的固定值
 *   - left 层：找到解后 left++，while 跳过所有相同值
 *   - right 层：找到解后 right--，while 跳过所有相同值
 *
 * 时间复杂度 O(n^2)：排序 O(n log n) + 外层 O(n) × 内层双指针 O(n)
 * 空间复杂度 O(1)：不计返回结果
 */
public class ThreeSum {

    public static void main(String[] args) {
        int[] nums = {-1, 0, 1, 2, -1, -4};
        System.out.println(threeSum(nums));
    }

    public static List<List<Integer>> threeSum(int[] nums) {
        List<List<Integer>> res = new ArrayList<>();
        Arrays.sort(nums);

        for (int i = 0; i < nums.length - 2; i++) {
            if (i > 0 && nums[i] == nums[i - 1]) {
                continue;
            }
            int left = i + 1;
            int right = nums.length - 1;
            while (left < right) {
                int sum = nums[i] + nums[left] + nums[right];
                if (sum == 0) {
                    res.add(Arrays.asList(nums[i], nums[left], nums[right]));
                    left++;
                    right--;
                    while (left < right && nums[left] == nums[left - 1]) left++;
                    while (left < right && nums[right] == nums[right + 1]) right--;
                } else if (sum > 0) {
                    right--;
                } else {
                    left++;
                }
            }
        }
        return res;
    }
}
