package algorithms;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class LongestConsecutive {


    public static void main(String[] args) {
        int[] inputs = {100, 4, 200, 1, 3, 2};
        System.out.println(longestConsecutive(inputs));
    }

    public static int longestConsecutive(int[] nums) {
        Set<Integer> numSet = Arrays.stream(nums)
                .boxed()
                .collect(Collectors.toSet());
        int longest = 0;
        for (Integer num : numSet) {
            int curNum = num;
            int currentLongest=1;
            if (!numSet.contains(curNum-1)){
                while (numSet.contains(curNum + 1)){
                    curNum++;
                    currentLongest++;
                }
            }
            if (currentLongest > longest){
                longest = currentLongest;
            }
        }
        return longest;
    }

}
