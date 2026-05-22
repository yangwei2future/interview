package algorithms;

import java.util.HashMap;

public class SubarraySum {

    /**
     * 560. 和为 K 的子数组
     *
     * 核心思路：前缀和 + HashMap（一遍遍历）
     *
     * 前缀和定义：preSum[j] = nums[0] + nums[1] + ... + nums[j]
     * 任意子数组 nums[i..j] 的和 = preSum[j] - preSum[i-1]
     *
     * 题目要求子数组和为 k，代入公式：
     *   preSum[j] - preSum[i-1] = k
     *   变形：preSum[i-1] = preSum[j] - k
     *
     * 翻译成人话：
     *   走到位置 j 时，前缀和为 curPrefixSum，
     *   看 map 里前面出现过多少次 (curPrefixSum - k)，
     *   有多少次，就有多少个子数组以 j 结尾且和为 k。
     *
     * 为什么 map 存的是"前缀和的值 → 出现次数"而不是"下标 → 前缀和"：
     *   因为需要快速查出"某个前缀和值在前面出现过几次"，跟下标无关。
     *
     * 为什么初始化 map.put(0, 1)：
     *   前缀和 0 表示"还没开始遍历任何元素时的状态"。
     *   当答案子数组正好从 nums[0] 开头时，需要 preSum[-1] = 0 来参与计算。
     *   例如 nums=[3], k=3 → curPrefixSum=3，需要找 3-3=0，必须命中才能得出答案。
     *
     * 为什么先查 map，再更新 map：
     *   防止 k=0 时把当前自己的前缀和也算进去。
     *   例如 nums=[0], k=0 → 先查 map(0)=1 count=1，再更新 map(0)=2。
     *   如果顺序反过来，就把自己当成"前面的前缀和"多算一次。
     *
     * 时间复杂度 O(n)，空间复杂度 O(n)
     */
    public int subarraySum(int[] nums, int k) {
        // key = 前缀和的值, value = 该前缀和出现的次数
        HashMap<Integer, Integer> curSum = new HashMap<>();
        // 前缀和为 0 出现过 1 次：处理子数组从 nums[0] 开始的情况
        curSum.put(0, 1);

        int res = 0;
        int curPrefixSum = 0;
        for (int num : nums) {
            curPrefixSum += num;
            // 先查：前面有多少个 curPrefixSum - k，就有多少个以当前位置结尾的答案
            res += curSum.getOrDefault(curPrefixSum - k, 0);
            // 再更新：把当前前缀和记录进去，留给后面的元素用
            curSum.put(curPrefixSum, curSum.getOrDefault(curPrefixSum, 0) + 1);
        }
        return res;
    }

    public static void main(String[] args) {
        SubarraySum start = new SubarraySum();

        // [1,1] 和 [1,1]（下标 [0,1] 和 [1,2]）
        System.out.println(start.subarraySum(new int[]{1, 1, 1}, 2)); // 2

        // [3] 单个元素就是答案，验证 map.put(0,1) 的必要性
        System.out.println(start.subarraySum(new int[]{3}, 3)); // 1

        // k=0 边界：先查再更新，防止把当前自己算进去
        System.out.println(start.subarraySum(new int[]{1, -1, 0}, 0)); // 3
    }
}
