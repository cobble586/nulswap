import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import org.checkerframework.checker.units.qual.A;
import io.nuls.contract.sdk.event.DebugEvent;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

/**
 * @title   Nulswap Router
 *
 * @author  Pedro G. S. Ferreira
 *
 */
public class NulswapRouter implements Contract{

    /** Variables **/
    private Address factory;                     // Factory
    private Address WNULS;                       // WNULS

    private static Address BURNER_ADDR = new Address("NULSd6HgsVSzCAJwLYBjvfP3NwbKCvV525GWn");

    /**
     * Constructor
     *
     * @param _factory Factory Address
     * @param _WNULS   WNULS Address
     */
    public NulswapRouter(Address _factory, Address _WNULS){
        factory = _factory;
        WNULS   = _WNULS;
    }

    /**
     * Ensure tx is done before deadline
     *
     * @param deadline The timestamp when the tx is not valid anymore
     */
    protected void ensure(BigInteger deadline) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0, "UniswapV2Router: EXPIRED");
    }

    /**
     * Fallback function in case someone transfer nuls to the contract
     */
    @Payable
    @Override
    public void _payable() {
        require(Msg.sender().equals(WNULS), "NulswapRouter: Only WNULS can call payable"); // only accept NULS via fallback from the WETH contract
    }

    /**
     *  **** ADD LIQUIDITY ****
     *
     * @param tokenA Token Address
     * @param tokenB Token Address
     * @param amountADesired Amount A
     * @param amountBDesired
     * @param amountAMin
     * @param amountBMin
     */
    private String _addLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger amountADesired,
            BigInteger amountBDesired,
            BigInteger amountAMin,
            BigInteger amountBMin
    ){


        // create the pair if it doesn't exist yet
        if (safeGetPair(tokenA, tokenB).equals(BURNER_ADDR)) {
            _createPair(tokenA, tokenB);
        }

        String resValues    = getReserves(tokenA, tokenB);
        String[] arrOfStr   = resValues.split(",", 2);
        BigInteger reserveA = new BigInteger(arrOfStr[0]);
        BigInteger reserveB = new BigInteger(arrOfStr[1]);

        BigInteger amountA, amountB;
        if (reserveA.compareTo(BigInteger.ZERO) == 0 && reserveB.compareTo(BigInteger.ZERO) == 0) {

            amountA = amountADesired;
            amountB = amountBDesired;

        } else {

            BigInteger amountBOptimal = quote(amountADesired, reserveA, reserveB);

            if (amountBOptimal.compareTo(amountBDesired) <= 0) {
                require(amountBOptimal.compareTo(amountBMin) >= 0, "NulswapV2Router: INSUFFICIENT_B_AMOUNT");
                amountA = amountADesired;
                amountB = amountBOptimal;
            } else {
                BigInteger amountAOptimal = quote(amountBDesired, reserveB, reserveA);
                require(amountAOptimal.compareTo(amountADesired) <= 0, "AmountAoptimal error");
                require(amountAOptimal.compareTo(amountAMin) >= 0, "NulswapV2Router: INSUFFICIENT_A_AMOUNT");
                amountA = amountAOptimal;
                amountB = amountBDesired;
            }

        }

        return amountA + "," + amountB;
    }

    /**
     *  **** ADD LIQUIDITY ****
     *
     * @param tokenA Token Address
     * @param tokenB Token Address
     * @param amountADesired Amount A
     * @param amountBDesired
     * @param amountAMin
     * @param amountBMin
     */
    public String addLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger amountADesired,
            BigInteger amountBDesired,
            BigInteger amountAMin,
            BigInteger amountBMin,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);

        String addLiqRes        = _addLiquidity(tokenA, tokenB, amountADesired, amountBDesired, amountAMin, amountBMin);

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountA      = new BigInteger(arrOfStr[0]);
        BigInteger amountB      = new BigInteger(arrOfStr[1]);

        Utils.emit(new DebugEvent("test1", "2"));

        Address pair            = safeGetPair(tokenA, tokenB);

        safeTransferFrom(tokenA, Msg.sender(), pair, amountA);
        safeTransferFrom(tokenB, Msg.sender(), pair, amountB);

        Utils.emit(new DebugEvent("test1", "3"));

        BigInteger liquidity    = safeMint(pair, to);

        return amountA + "," + amountB  + "," + liquidity;
    }

    /**
     *  **** ADD LIQUIDITY WITH NULS ****
     *
     * @param token Token Address
     * @param amountTokenDesired Amount A
     * @param amounttokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     */
    @Payable
    public String addLiquidityNuls(
            Address token,
            BigInteger amountTokenDesired,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);

        String addLiqRes = _addLiquidity(
                token,
                WNULS,
                amountTokenDesired,
                Msg.value(),
                amountTokenMin,
                amountETHMin
        );

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        Address pair = safeGetPair(token, WNULS);
        safeTransferFrom(token, Msg.sender(), pair, amountToken);

        depositNuls(amountETH);

        safeTransfer(WNULS, pair, amountETH);

        BigInteger liquidity =  safeMint(pair, to); // IUniswapV2Pair(pair).mint(to);

        if (Msg.value().compareTo(amountETH) > 0)
            safeTransferETH(Msg.sender(), Msg.value().subtract(amountETH)); // refund dust eth, if any

        return amountToken + "," + amountETH + "," + liquidity;
    }

    /**
     * **** REMOVE LIQUIDITY ****
     *
     *
     * @param tokenA
     */
    public String removeLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger liquidity,
            BigInteger amountAMin,
            BigInteger amountBMin,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);

        Address pair = safeGetPair(tokenA, tokenB);
        safeTransferFrom(pair, Msg.sender(), pair, liquidity);

        String[] arrOfStr  = safeBurn(pair, to).split(",", 2);
        BigInteger amount0 = new BigInteger(arrOfStr[0]);
        BigInteger amount1 = new BigInteger(arrOfStr[1]);

        String[] arrOfStr2 = sortTokens(tokenA, tokenB).split(",", 2);
        Address token0     = new Address(arrOfStr2[0]);

        BigInteger amountA, amountB;
        if(tokenA.equals(token0)) {
            amountA = amount0;
            amountB = amount1;
        }else{
            amountA = amount1;
            amountB = amount0;
        }

        require(amountA.compareTo(amountAMin) >= 0, "NulswapV2Router: INSUFFICIENT_A_AMOUNT");
        require(amountB.compareTo(amountBMin) >= 0, "NulswapV2Router: INSUFFICIENT_B_AMOUNT");

        return amountA + "," + amountB;
    }

    /**
     *
     * @param token
     * @param liquidity
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     * */
    public String removeLiquidityETH(
            Address token,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);

       String resVal = removeLiquidity(
                token,
                WNULS,
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        String[] arrOfStr       = resVal.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        safeTransfer(token, to, amountToken);
        withdrawNuls(amountETH);

        safeTransferETH(to, amountETH);

        return amountToken + "," + amountETH;
    }

    // **** SWAP ****
    // requires the initial amount to have already been sent to the first pair
    private void _swap(String[]  amounts, String[] path, Address _to){

        for (int i = 0; i < path.length - 1; i++) {

            Address input  = new Address(path[i]);
            Address output = new Address(path[i + 1]);

            String[] arrOfStr2      = sortTokens(input, output).split(",", 2);
            Address token0          = new Address(arrOfStr2[0]);
            BigInteger amountOut    = new BigInteger(amounts[i + 1]);

            BigInteger amount0Out, amount1Out;
            if(input.equals(token0)){
                amount0Out = BigInteger.ZERO;
                amount1Out = amountOut;
            }else{
                amount0Out = amountOut;
                amount1Out = BigInteger.ZERO;
            }

            Address to = (i < path.length - 2 ) ? safeGetPair(output, new Address(path[i + 2])) : _to;

           // IUniswapV2Pair(safeGetPair(input, output)).swap(amount0Out, amount1Out, to);
            safeSwap(safeGetPair(input, output), amount0Out, amount1Out, to);
        }

    }

    /**
     *
     *
     * @param amountIn
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    public String[] swapExactTokensForTokens(
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);

        String[] amounts = getAmountsOut(amountIn, path);
        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));

        _swap(amounts, path, to);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path Array of tokens
     * @param to Address that will receive the output token
     * @param deadline
     */
    public String[] swapTokensForExactTokens(
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);

        String[] amounts = getAmountsIn(amountOut, path);
        require(new BigInteger(amounts[0]).compareTo(amountInMax) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));

        _swap(amounts, path, to);
        return amounts;
    }


    /**
     *
     *
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    @Payable
   public String[] swapExactETHForTokens(
           BigInteger amountOutMin,
           String[] path,
           Address to,
           BigInteger deadline
    ){
        ensure(deadline);

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsOut(Msg.value(), path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        depositNuls(new BigInteger(amounts[0])); //IWETH(WETH).deposit{value: amounts[0]}();

        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Transfer failed");
        _swap(amounts, path, to);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path
     * @param to
     * @param deadline
     * */
    public String[] swapTokensForExactETH(
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline)
    {
        ensure(deadline);
        require(path[path.length - 1].equals(WNULS), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);

        require(new BigInteger(amounts[0]).compareTo(amountInMax) <= 0, "UniswapV2Router: EXCESSIVE_INPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());
        withdrawNuls(new BigInteger(amounts[amounts.length - 1]));
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));
        return amounts;
    }

    /**
     *
     * @param amountIn
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     */
    public String[] swapExactTokensForETH(
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);

        require(new Address(path[path.length - 1]).equals(WNULS),"UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsOut(amountIn, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair(new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());

        withdrawNuls(new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * @param to
     * @param deadline
     */
    @Payable
    public String[] swapETHForExactTokens(
            BigInteger amountOut,
            String[] path,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);
        require(new BigInteger(amounts[0]).compareTo(Msg.value()) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");

        depositNuls(new BigInteger(amounts[0]));
        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, to);

        if (Msg.value().compareTo(new BigInteger(amounts[0])) > 0) safeTransferETH(Msg.sender(), Msg.value().subtract(new BigInteger(amounts[0]))); // refund dust eth, if any
        return amounts;
    }

    /**
     *
     * @param tokenA
     * @param tokenB
     * */
    // returns sorted token addresses, used to handle return values from pairs sorted in this order
    private String sortTokens(Address tokenA, Address tokenB){

        require(!tokenA.equals(tokenB), "NulswapV2Library: IDENTICAL_ADDRESSES");

        Address token0, token1;
        if(tokenA.hashCode() < tokenB.hashCode()){
            token0 = tokenA;
            token1 = tokenB;
        }else{
            token0 = tokenB;
            token1 = tokenA;
        }

        require(token0 != null, "UniswapV2Library: ZERO_ADDRESS");
        return token0 + "," + token1;
    }

    /**
     * @param tokenA
     * @param tokenB
     * */
    // fetches and sorts the reserves for a pair
    private String getReserves(
            Address tokenA,
            Address tokenB
    ){
        String[] arrOfStr2  = sortTokens(tokenA, tokenB).split(",", 2);
        Address token0      = new Address(arrOfStr2[0]);

        String[] arrOfStr3  = safeGetReserves(safeGetPair(tokenA, tokenB)).split(",", 3);
        BigInteger reserve0 = new BigInteger(arrOfStr3[0]);
        BigInteger reserve1 = new BigInteger(arrOfStr3[1]);

        BigInteger reserveA, reserveB;
        if (tokenA.equals(token0)){
            reserveA = reserve0;
            reserveB = reserve1;
        } else {
            reserveA = reserve1;
            reserveB = reserve0;
        }

        return reserveA + "," + reserveB;
    }


    /**
     *
     * @param amountA
     * @param reserveA
     * @param reserveB
     * */
    @View
    public BigInteger quote(
            BigInteger amountA,
            BigInteger reserveA,
            BigInteger reserveB
    ){
        require(amountA.compareTo(BigInteger.ZERO) > 0, "UniswapV2Library: INSUFFICIENT_AMOUNT");
        require(reserveA.compareTo(BigInteger.ZERO) > 0 && reserveB.compareTo(BigInteger.ZERO) > 0, "UniswapV2Library: INSUFFICIENT_LIQUIDITY");
        BigInteger amountB = amountA.multiply(reserveB).divide(reserveA);
        return amountB;
    }

    /**
     *
     * @param amountIn
     * @param reserveIn
     * @param reserveOut
     * */
    @View
    public BigInteger getAmountOut(
            BigInteger amountIn,
            BigInteger reserveIn,
            BigInteger reserveOut
    ){

        require(amountIn.compareTo(BigInteger.ZERO) > 0,"UniswapV2Library: INSUFFICIENT_INPUT_AMOUNT");
        require(reserveIn.compareTo(BigInteger.ZERO) > 0 && reserveOut.compareTo(BigInteger.ZERO) > 0, "UniswapV2Library: INSUFFICIENT_LIQUIDITY");

        BigInteger amountInWithFee = amountIn.multiply(BigInteger.valueOf(997));
        BigInteger numerator = amountInWithFee.multiply(reserveOut);
        BigInteger denominator = reserveIn.multiply(BigInteger.valueOf(1000)).add(amountInWithFee);
        BigInteger amountOut = numerator.divide(denominator);

        return amountOut;
    }

    /**
     *
     * @param amountOut
     * @param reserveIn
     * @param reserveOut
     * */
    @View
    public BigInteger getAmountIn(
            BigInteger amountOut,
            BigInteger reserveIn,
            BigInteger reserveOut
    ){

        require(amountOut.compareTo(BigInteger.ZERO) > 0, "UniswapV2Library: INSUFFICIENT_OUTPUT_AMOUNT");
        require(reserveIn.compareTo(BigInteger.ZERO) > 0 && reserveOut.compareTo(BigInteger.ZERO) > 0, "UniswapV2Library: INSUFFICIENT_LIQUIDITY");

        BigInteger numerator = reserveIn.multiply(amountOut).multiply(BigInteger.valueOf(1000));
        BigInteger denominator = (reserveOut.subtract(amountOut)).multiply(BigInteger.valueOf(997));
        BigInteger amountIn = (numerator.divide(denominator)).add(BigInteger.ONE);

        return amountIn;
    }

    /**
     *
     * @param amountIn
     * @param path
     * */
    @View
    public String[] getAmountsOut(BigInteger amountIn, String[] path){
        require(path.length >= 2, "UniswapV2Library: INVALID_PATH");
        String[] amounts = new String[path.length];
        amounts[0] = amountIn.toString();
        for (int i = 0; i < path.length - 1; i++) {

            String resVal = getReserves(new Address(path[i]), new Address(path[i + 1]));

            String[] arrOfStr3    = getReserves(new Address(path[i]), new Address(path[i + 1])).split(",", 3);
            BigInteger reserveIn  = new BigInteger(arrOfStr3[0]);
            BigInteger reserveOut = new BigInteger(arrOfStr3[1]);

            amounts[i + 1] = getAmountOut( new BigInteger(amounts[i]), reserveIn, reserveOut).toString();
        }
        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * */
    @View
    public String[] getAmountsIn(BigInteger amountOut, String[] path) {
        require(path.length >= 2, "UniswapV2Library: INVALID_PATH");
        String[] amounts = new String[path.length];

        amounts[amounts.length - 1] = amountOut.toString();

        for (int i = path.length - 1; i > 0; i--) {

            String[] arrOfStr3    = getReserves( new Address(path[i - 1]), new Address(path[i])).split(",", 3);
            BigInteger reserveIn  = new BigInteger(arrOfStr3[0]);
            BigInteger reserveOut = new BigInteger(arrOfStr3[1]);

            amounts[i - 1] = getAmountIn(new BigInteger(amounts[i]), reserveIn, reserveOut).toString();
        }
        return amounts;
    }


    /**
     *
     * @param token
     * @param account
     * */
    private BigInteger safeBalanceOf(@Required Address token, @Required Address account){
        String[][] argsM = new String[][]{new String[]{account.toString()}};
        return new BigInteger(token.callWithReturnValue("balanceOf", "", argsM, BigInteger.ZERO));

    }

    /**
     *
     * @param pair
     * @param amount0Out
     * @param amount1Out
     * @param to
     * */
    private BigInteger safeSwap(
            @Required Address pair,
            BigInteger amount0Out,
            BigInteger amount1Out,
            @Required Address to
    ){
        String[][] argsM = new String[][]{new String[]{amount0Out.toString()}, new String[]{amount1Out.toString()}, new String[]{to.toString()}};
        return new BigInteger(pair.callWithReturnValue("swap", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param pair
     */
    private String safeGetReserves(@Required Address pair){
        String[][] argsM = new String[][]{};
        return pair.callWithReturnValue("getReserves", "", argsM, BigInteger.ZERO);
    }

    /**
     *
     * @param pair
     * @param to
     */
    private BigInteger safeMint(@Required Address pair, @Required Address to){
        String[][] argsM = new String[][]{new String[]{to.toString()}};
        return new BigInteger(pair.callWithReturnValue("mint", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param pair
     * @param to
     */
    private String safeBurn(@Required Address pair, @Required Address to){
        String[][] argsM = new String[][]{new String[]{to.toString()}};
        return pair.callWithReturnValue("burn", "", argsM, BigInteger.ZERO);
    }


    /**
     *
     * @param token
     * */
    private BigInteger safeTotalSupply(@Required Address token){
        String[][] argsM = new String[][]{};
        return new BigInteger(token.callWithReturnValue("totalSupply", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param tokenA
     * @param tokenB
     * */
    private Address safeGetPair(@Required Address tokenA, @Required Address tokenB){
        String[][] argsM = new String[][]{new String[]{tokenA.toString()}, new String[]{tokenB.toString()}};
        return new Address(factory.callWithReturnValue("getPair", "", argsM, BigInteger.ZERO));
    }

    /**
     *  Transfer token from msg.sender to the recipient address
     *
     * @param token Token Address
     * @param recipient Recipient Address
     * @param amount Amount to Transfer
     */
    private Boolean safeTransfer(
            @Required Address token,
            @Required Address recipient,
            @Required BigInteger amount
    ){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapRouter: Failed to transfer");
        return b;
    }

    /**
     *
     * Safe Transfer
     *
     * @param recipient
     * @param amount
     * */
    private void safeTransferETH(
            @Required Address recipient,
            @Required BigInteger amount
    ){
            recipient.transfer(amount);
    }

    /**
     *  Transfer token from the address from to the recipient address
     *
     * @param token Token Address
     * @param from The Address from where the tokens will be retrieved
     * @param recipient Recipient Address
     * @param amount Amount to Transfer
     */
    private void safeTransferFrom(
            @Required Address token,
            @Required Address from,
            @Required Address recipient,
            @Required BigInteger amount
    ){
        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transferFrom", "", args, BigInteger.ZERO));
        require(b, "NulswapRouter: Failed to transfer");
    }


    /**
     *
     * Create Pair
     *
     * @param token0
     * @param token1
     * */
    private Address _createPair(Address token0, Address token1){

        String[][] args = new String[][]{ new String[]{token0.toString()}, new String[]{token1.toString()}};
        Address b = new Address(factory.callWithReturnValue("createPair", "", args, BigInteger.ZERO));
        return b;

    }

    //Deposit nuls and get wnuls in return
    private void depositNuls(@Required BigInteger v) {

        //Require that the amount sent is equal to the amount requested - Do not remove this verification
        require(Msg.value().compareTo(v) >= 0, "NulswapV1: Value does not match the amount sent");

        //Create arguments and call the deposit function
        String[][] args = new String[][]{new String[]{v.toString()}};
        String rDeposit = WNULS.callWithReturnValue("deposit", null, args, v);

        //require that the deposit was successful
        require(new Boolean(rDeposit), "NulswapV1: Deposit did not succeed");
        //emit(new TokenPurchase(wNull, v, v));
    }

    /**
     * Withdraw nuls from the wnuls contract - must be always private - when I say always is ALWAYS
     *
     * @param v Amount of WNULS to convert to NULS
     */
    private void withdrawNuls(@Required BigInteger v) {

        /*create approve to transfer tokens to the wnuls contract*/
        String[][] argsApprove = new String[][]{new String[]{WNULS.toString()}, new String[]{v.toString()}};
        String rApprove = WNULS.callWithReturnValue("approve", null, argsApprove, BigInteger.ZERO);

        require(new Boolean(rApprove), "NulswapRouter: Approve did not succeeded!");

        //Create arguments and call the withdraw function
        String[][] args = new String[][]{new String[]{v.toString()}, new String[]{Msg.sender().toString()}};
        String rWithdraw = WNULS.callWithReturnValue("withdraw", "", args, BigInteger.ZERO);

        //Require that the withdraw was successful
        require(new Boolean(rWithdraw), "NulswapRouter: Withdraw did not succeed!");
        //emit(new TokenPurchase(WNULS, v, v));
    }

}