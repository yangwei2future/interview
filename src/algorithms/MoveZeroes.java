package algorithms;

public class MoveZeroes {

    public void getMoveZeroes(int[] nums) {
        int curZeroNum = 0;
        int length = nums.length;
        for (int i = 0; i < length; i++) {
            if (nums[i] == 0) {
                curZeroNum++;
            }else{
                nums[i - curZeroNum] = nums[i];
            }
        }
        for (int i = length - curZeroNum; i <length; i++) {
            nums[i] = 0;
        }
    }

}
