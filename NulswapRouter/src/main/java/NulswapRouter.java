import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import org.checkerframework.checker.units.qual.A;

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

    Address public factory;                     // Factory
    Address public WNULS;                       // WNULS
    private static Address BURNER_ADDR = new Address("");

    public NulswapRouter(Address _factory, Address _WNULS){
        factory = _factory;
        WNULS = _WNULS;
    }

    protected void ensure(BigInteger deadline) {
        require(deadline.compareTo(Block.timestamp()) >= 0, "UniswapV2Router: EXPIRED");
    }

    /*
     * Fallback function in case someone transfer nuls to the contract
     * */
    @Payable
    @Override
    public void _payable() {
        require(Msg.sender().equals(WETH)); // only accept ETH via fallback from the WETH contract
    }

    // **** ADD LIQUIDITY ****
    private String _addLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger amountADesired,
            BigInteger amountBDesired,
            BigInteger amountAMin,
            BigInteger amountBMin
    ){
        // create the pair if it doesn't exist yet
        if (IUniswapV2Factory(factory).getPair(tokenA, tokenB) == BURNER_ADDR) {
            _createPair(tokenA, tokenB);
        }

        String resValues = getReserves(factory, tokenA, tokenB);

        String[] arrOfStr = resValues.split(",", 2);
        BigInteger reserveA = new BigInteger(arrOfStr[0]);
        BigInteger reserveB = new BigInteger(arrOfStr[1]);

        if (reserveA.compareTo(BigInteger.ZERO) == 0 && reserveBcompareTo(BigInteger.ZERO) == 0) {
            BigInteger amountA = amountADesired;
            BigInteger amountB = amountBDesired;
        } else {
            BigInteger amountBOptimal = quote(amountADesired, reserveA, reserveB);
            if (amountBOptimal.compareTo(amountBDesired) <= 0) {
                require(amountBOptimal.compareTo(amountBMin) >= 0, 'UniswapV2Router: INSUFFICIENT_B_AMOUNT');
                amountA = amountADesired
                amountB) amountBOptimal;
            } else {
                BigInteger amountAOptimal = quote(amountBDesired, reserveB, reserveA);
                assert(amountAOptimal <= amountADesired);
                require(amountAOptimal.compareTo(amountAMin) >= 0, 'UniswapV2Router: INSUFFICIENT_A_AMOUNT');
                amountA = amountAOptimal;
                amountB = amountBDesired;
            }
        }
        return amountA + "," + amountB;
    }


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
        BigInteger addLiqRes = _addLiquidity(tokenA, tokenB, amountADesired, amountBDesired, amountAMin, amountBMin);

        String[] arrOfStr = addLiqRes.split(",", 2);
        BigInteger amountA = new BigInteger(arrOfStr[0]);
        BigInteger amountB = new BigInteger(arrOfStr[1]);

        Address pair = pairFor(factory, tokenA, tokenB);

        safeTransferFrom(tokenA, Msg.sender(), pair, amountA);
        safeTransferFrom(tokenB, Msg.sender(), pair, amountB);

        liquidity = IUniswapV2Pair(pair).mint(to);

        return amountA + "," + amountB  + "," + liquidity;
    }

    @Payable
    public String addLiquidityETH(
            Address token,
            BigInteger amountTokenDesired,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    )  returns (uint amountToken, uint amountETH, uint liquidity) {
        ensure(deadline);
        (amountToken, amountETH) = _addLiquidity(
                token,
                WETH,
                amountTokenDesired,
                msg.value,
                amountTokenMin,
                amountETHMin
        );
        Address pair = UniswapV2Library.pairFor(factory, token, WETH);
        safeTransferFrom(token, Msg.sender(), pair, amountToken);

        depositNuls(amountETH);

        safeTransfer(WNULS, pair, amountETH);
        BigInteger liquidity = IUniswapV2Pair(pair).mint(to);

        if (msg.value > amountETH) safeTransferETH(msg.sender(), Msg.value() - amountETH); // refund dust eth, if any

        return amountToken+","+amountETH+","+liquidity;
    }

    // **** REMOVE LIQUIDITY ****
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
        Address pair = pairFor(factory, tokenA, tokenB);
        safeTransferFrom(pair, Msg.sender(), pair, liquidity);

        (uint amount0, uint amount1) = IUniswapV2Pair(pair).burn(to);

        (address token0,) = sortTokens(tokenA, tokenB);
        (amountA, amountB) = tokenA == token0 ? (amount0, amount1) : (amount1, amount0);
        require(amountA.compareTo(amountAMin) >= 0, 'UniswapV2Router: INSUFFICIENT_A_AMOUNT');
        require(amountB.compareTo(amountBMin) >= 0, 'UniswapV2Router: INSUFFICIENT_B_AMOUNT');
        return amountA + "," + amountB;
    }

    public String removeLiquidityETH(
            Address token,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        (BigInteger amountToken, BigInteger amountETH) = removeLiquidity(
                token,
                WETH,
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        safeTransfer(token, to, amountToken);
        IWETH(WETH).withdraw(amountETH);
        safeTransferETH(to, amountETH);
        return amountToken+","+amountETH;
    }

    // **** SWAP ****
    // requires the initial amount to have already been sent to the first pair
    function _swap(uint[] memory amounts, address[] memory path, address _to) private {
        for (uint i; i < path.length - 1; i++) {
            (address input, address output) = (path[i], path[i + 1]);
            (address token0,) = UniswapV2Library.sortTokens(input, output);
            uint amountOut = amounts[i + 1];
            (uint amount0Out, uint amount1Out) = input == token0 ? (uint(0), amountOut) : (amountOut, uint(0));
            address to = i < path.length - 2 ? UniswapV2Library.pairFor(factory, output, path[i + 2]) : _to;
            IUniswapV2Pair(UniswapV2Library.pairFor(factory, input, output)).swap(amount0Out, amount1Out, to, new bytes(0));
        }
    }
    function swapExactTokensForTokens(
            uint amountIn,
            uint amountOutMin,
            address[] calldata path,
            address to,
            uint deadline
    ) external override ensure(deadline) returns (uint[] memory amounts) {
        amounts = UniswapV2Library.getAmountsOut(factory, amountIn, path);
        require(amounts[amounts.length - 1] >= amountOutMin, 'UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT');
        TransferHelper.safeTransferFrom(path[0], msg.sender, UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, to);
    }

    public BigInteger[] swapTokensForExactTokens(
            uint amountOut,
            uint amountInMax,
            Address[] calldata path,
            Address to,
            uint deadline
    ) external override  returns (uint[] memory amounts) {
        ensure(deadline);
        amounts = UniswapV2Library.getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= amountInMax, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        TransferHelper.safeTransferFrom(path[0], msg.sender, UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, to);
        return amounts;
    }

    @Payable
   public BigInteger[] swapExactETHForTokens(
           uint amountOutMin,
           address[] calldata path,
           address to,
           uint deadline)
    {
        ensure(deadline);
        require(path[0] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = getAmountsOut(factory, msg.value, path);
        require(amounts[amounts.length - 1] >= amountOutMin, 'UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT');
        IWETH(WETH).deposit{value: amounts[0]}();
        assert(IWETH(WETH).transfer(UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]));
        _swap(amounts, path, to);
        return amounts;
    }

    public BigInteger[] swapTokensForExactETH(
            uint amountOut,
            uint amountInMax,
            address[] calldata path,
            Address to,
            uint deadline)
    {
        ensure(deadline);
        require(path[path.length - 1] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= amountInMax, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        safeTransferFrom(path[0], Msg.sender(), UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, address(this));
        IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferETH(to, amounts[amounts.length - 1]);
        return amounts;
    }

    public BigInteger[] swapExactTokensForETH(
            uint amountIn,
            uint amountOutMin,
            Address[] path,
            address to,
            uint deadline
    ){
        ensure(deadline);
        require(path[path.length - 1].equals(WNULS), 'UniswapV2Router: INVALID_PATH');
        amounts = getAmountsOut(factory, amountIn, path);
        require(amounts[amounts.length - 1] >= amountOutMin, 'UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT');
        safeTransferFrom(path[0], msg.sender, pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, address(this));
        IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferETH(to, amounts[amounts.length - 1]);
        return amounts;
    }

    @Payable
    public BigInteger[] swapETHForExactTokens(
            BigInteger amountOut,
            address[] calldata path,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);
        require(path[0] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= msg.value, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        IWETH(WETH).deposit{value: amounts[0]}();
        assert(IWETH(WETH).transfer(pairFor(factory, path[0], path[1]), amounts[0]));
        _swap(amounts, path, to);
        if (msg.value > amounts[0]) safeTransferETH(msg.sender, msg.value - amounts[0]); // refund dust eth, if any
        return amounts;
    }

    // fetches and sorts the reserves for a pair
    @View
    private getReserves(address factory, address tokenA, address tokenB) internal view returns (uint reserveA, uint reserveB) {
        (address token0,) = sortTokens(tokenA, tokenB);
        (uint reserve0, uint reserve1,) = IUniswapV2Pair(pairFor(factory, tokenA, tokenB)).getReserves();
        (reserveA, reserveB) = tokenA == token0 ? (reserve0, reserve1) : (reserve1, reserve0);
    }


    @View
    public BigInteger quote(BigInteger amountA, BigInteger reserveA, BigInteger reserveB){
        require(amountA.compareTo(BigInteger.ZERO) > 0, 'UniswapV2Library: INSUFFICIENT_AMOUNT');
        require(reserveA.compareTo(BigInteger.ZERO) > 0 && reserveB.compareTo(BigInteger.ZERO) > 0, 'UniswapV2Library: INSUFFICIENT_LIQUIDITY');
        BigInteger amountB = amountA.multiply(reserveB).divide(reserveA);
        return amountB;
    }

    @View
    public BigInteger getAmountOut(BigInteger amountIn, BigInteger reserveIn, uint reserveOut){
        require(amountIn > 0, 'UniswapV2Library: INSUFFICIENT_INPUT_AMOUNT');
        require(reserveIn > 0 && reserveOut > 0, 'UniswapV2Library: INSUFFICIENT_LIQUIDITY');
        BigInteger amountInWithFee = amountIn.multiply(997);
        BigInteger numerator = amountInWithFee.multiply(reserveOut);
        BigInteger denominator = reserveIn.multiply(1000).add(amountInWithFee);
        amountOut = numerator.divide(denominator);
        return amountOut;
    }

    @View
    public BigInteger getAmountIn(uint amountOut, uint reserveIn, uint reserveOut){
        return getAmountOut(amountOut, reserveIn, reserveOut);
    }

    @View
    function getAmountsOut(uint amountIn, address[] memory path) public view override returns (uint[] memory amounts) {
        return UniswapV2Library.getAmountsOut(factory, amountIn, path);
    }

    @View
    function getAmountsIn(uint amountOut, address[] memory path) public view override returns (uint[] memory amounts) {
        return UniswapV2Library.getAmountsIn(factory, amountOut, path);
    }


    private BigInteger safeBalanceOf(@Required Address token, @Required Address account){
        String[][] argsM = new String[][]{new String[]{account.toString()}};
        return new BigInteger(token.callWithReturnValue("balanceOf", "", argsM, BigInteger.ZERO));

    }

    private BigInteger safeTotalSupply(@Required Address token){
        String[][] argsM = new String[][]{};
        return new BigInteger(token.callWithReturnValue("totalSupply", "", argsM, BigInteger.ZERO));

    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NEII-V1: Failed to transfer");
    }

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
        require(b, "NulswapV1: Failed to transfer");
    }


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

}