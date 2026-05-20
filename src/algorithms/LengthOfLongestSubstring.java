package algorithms;

import java.util.HashMap;
import java.util.Map;

public class LengthOfLongestSubstring {

    /**
     * 3. 无重复字符的最长子串 — 滑动窗口解法
     * <p>
     * 滑动窗口思想：
     * 两个指针 (left, right) 框住一个「无重复字符的窗口」，
     * 窗口像会伸缩的窗户一样在字符串上滑动 ——
     * - right 右移扩张：新字符进来，不重复就收下，更新最大长度
     * - left  右移收缩：新字符和窗口内某个字符重复了，
     *   left 直接跳到重复字符的下一个位置，把重复的挤出去
     * <p>
     * 图解 "abcabcbb"：
     * <pre>
     * "a b c a b c b b"    初始: L=0, max=0, map={}
     *  L     R              R=0..2: a,b,c 无重复 → max=3, map={a:0,b:1,c:2}
     *  └─────┘
     * "a b c a b c b b"    R=3 'a': 重复，位置=0>=L → L 跳到 0+1=1
     *    L     R            窗口 "bca", max=3, map={a:3,b:1,c:2}
     *    └─────┘
     * "a b c a b c b b"    R=4 'b': 重复，位置=1>=L → L 跳到 1+1=2
     *      L     R          窗口 "cab", max=3
     *      └─────┘
     * </pre>
     * 关键判断 {@code prevIdx >= left}：
     * 重复字符的位置必须在窗口内才算重复 ——
     * 在 L 左边的不算，已经不在窗口里了。
     * <p>
     * 复杂度：时间 O(n)，R 走一遍 L 走一遍；空间 O(m)，m = 字符集大小
     */
    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> map = new HashMap<>();
        int max = 0;
        int left = 0;

        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            // 字符已在窗口内 → 左指针跳到重复字符的下一个位置
            Integer prevIdx = map.get(c);
            if (prevIdx != null && prevIdx >= left) {
                left = prevIdx + 1;
            }
            map.put(c, right);
            max = Math.max(max, right - left + 1);
        }
        return max;
    }

    public static void main(String[] args) {
        LengthOfLongestSubstring sol = new LengthOfLongestSubstring();

        System.out.println(sol.lengthOfLongestSubstring("abcabcbb")); // 3
        System.out.println(sol.lengthOfLongestSubstring("bbbbb"));    // 1
        System.out.println(sol.lengthOfLongestSubstring("pwwkew"));   // 3
        System.out.println(sol.lengthOfLongestSubstring(""));         // 0
        System.out.println(sol.lengthOfLongestSubstring(" "));        // 1
        System.out.println(sol.lengthOfLongestSubstring("au"));       // 2
        System.out.println(sol.lengthOfLongestSubstring("abba"));     // 2（关键边界：L只能前进不能后退）
    }

    public int lengthOfLongestSubstring2(String s) {
        int res = 0;
        int left = 0;
        HashMap<Character, Integer> map = new HashMap<>();
        char[] charArray = s.toCharArray();
        for (int right = 0; right < charArray.length; right++) {
            char c = charArray[right];
            Integer pos = map.get(c);
            if (pos != null && pos >= left) {
                left = pos + 1;
            }
            map.put(c, right);
            res = Math.max(res, right-left+1);
        }
        return res;
    }
}
