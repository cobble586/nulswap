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
 * @title   Nuls Oracles Revenue Distribution Contract
 *
 * @dev     Allows for users to deposit Nuls Oracle Tokens (ORA)
 *          and earn revenue from the Nuls Oracles Project.
 *          After deposited users will no longer be able to
 *          withdraw the tokens, meanign that by depositing
 *          they are burning their tokens.
 *
 * @author  Pedro G. S. Ferreira
 *
 */
public class NulswapRouter implements Contract{

    Address public factory;
    Address public WNULS;

    protected void ensure(BigInteger deadline) {
        require(deadline.compareTo(Block.timestamp()) >= 0, "UniswapV2Router: EXPIRED");
    }

    public NulswapRouter(Address _factory, Address _WETH){
        factory = _factory;
        WETH = _WETH;
    }

    receive() external payable {
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
    ) private returns (uint amountA, uint amountB) {
        // create the pair if it doesn't exist yet
        if (IUniswapV2Factory(factory).getPair(tokenA, tokenB) == address(0)) {
            IUniswapV2Factory(factory).createPair(tokenA, tokenB);
        }
        (uint reserveA, uint reserveB) = UniswapV2Library.getReserves(factory, tokenA, tokenB);
        if (reserveA == 0 && reserveB == 0) {
            (amountA, amountB) = (amountADesired, amountBDesired);
        } else {
            uint amountBOptimal = UniswapV2Library.quote(amountADesired, reserveA, reserveB);
            if (amountBOptimal <= amountBDesired) {
                require(amountBOptimal >= amountBMin, 'UniswapV2Router: INSUFFICIENT_B_AMOUNT');
                (amountA, amountB) = (amountADesired, amountBOptimal);
            } else {
                uint amountAOptimal = UniswapV2Library.quote(amountBDesired, reserveB, reserveA);
                assert(amountAOptimal <= amountADesired);
                require(amountAOptimal >= amountAMin, 'UniswapV2Router: INSUFFICIENT_A_AMOUNT');
                (amountA, amountB) = (amountAOptimal, amountBDesired);
            }
        }
        return amountA+","+amountB;
    }
    public String addLiquidity(
            Address tokenA,
            Address tokenB,
            uint amountADesired,
            uint amountBDesired,
            uint amountAMin,
            uint amountBMin,
            address to,
            BigInteger deadline
    ) ensure(deadline) returns (uint amountA, uint amountB, uint liquidity) {
        (amountA, amountB) = _addLiquidity(tokenA, tokenB, amountADesired, amountBDesired, amountAMin, amountBMin);
        address pair = UniswapV2Library.pairFor(factory, tokenA, tokenB);
        TransferHelper.safeTransferFrom(tokenA, msg.sender, pair, amountA);
        TransferHelper.safeTransferFrom(tokenB, msg.sender, pair, amountB);
        liquidity = IUniswapV2Pair(pair).mint(to);
    }
    public String addLiquidityETH(
            address token,
            uint amountTokenDesired,
            uint amountTokenMin,
            BigInteger amountETHMin,
            address to,
            BigInteger deadline
    ) external override payable ensure(deadline) returns (uint amountToken, uint amountETH, uint liquidity) {
        (amountToken, amountETH) = _addLiquidity(
                token,
                WETH,
                amountTokenDesired,
                msg.value,
                amountTokenMin,
                amountETHMin
        );
        address pair = UniswapV2Library.pairFor(factory, token, WETH);
        TransferHelper.safeTransferFrom(token, msg.sender, pair, amountToken);
        IWETH(WETH).deposit{value: amountETH}();
        assert(IWETH(WETH).transfer(pair, amountETH));
        liquidity = IUniswapV2Pair(pair).mint(to);
        if (msg.value > amountETH) TransferHelper.safeTransferETH(msg.sender, msg.value - amountETH); // refund dust eth, if any
    }

    // **** REMOVE LIQUIDITY ****
    public String removeLiquidity(
            address tokenA,
            address tokenB,
            uint liquidity,
            uint amountAMin,
            uint amountBMin,
            address to,
            uint deadline
    ) ensure(deadline) returns (uint amountA, uint amountB) {
        ensure(deadline);
        address pair = UniswapV2Library.pairFor(factory, tokenA, tokenB);
        IUniswapV2Pair(pair).transferFrom(msg.sender, pair, liquidity); // send liquidity to pair
        (uint amount0, uint amount1) = IUniswapV2Pair(pair).burn(to);
        (address token0,) = UniswapV2Library.sortTokens(tokenA, tokenB);
        (amountA, amountB) = tokenA == token0 ? (amount0, amount1) : (amount1, amount0);
        require(amountA >= amountAMin, 'UniswapV2Router: INSUFFICIENT_A_AMOUNT');
        require(amountB >= amountBMin, 'UniswapV2Router: INSUFFICIENT_B_AMOUNT');
    }
    function removeLiquidityETH(
            address token,
            uint liquidity,
            uint amountTokenMin,
            uint amountETHMin,
            address to,
            uint deadline
    ) public override ensure(deadline) returns (uint amountToken, uint amountETH) {
        (amountToken, amountETH) = removeLiquidity(
                token,
                WETH,
                liquidity,
                amountTokenMin,
                amountETHMin,
                address(this),
                deadline
        );
        TransferHelper.safeTransfer(token, to, amountToken);
        IWETH(WETH).withdraw(amountETH);
        TransferHelper.safeTransferETH(to, amountETH);
    }
    function removeLiquidityWithPermit(
            address tokenA,
            address tokenB,
            uint liquidity,
            uint amountAMin,
            uint amountBMin,
            address to,
            uint deadline,
            bool approveMax, uint8 v, bytes32 r, bytes32 s
    ) external override returns (uint amountA, uint amountB) {
        address pair = UniswapV2Library.pairFor(factory, tokenA, tokenB);
        uint value = approveMax ? uint(-1) : liquidity;
        IUniswapV2Pair(pair).permit(msg.sender, address(this), value, deadline, v, r, s);
        (amountA, amountB) = removeLiquidity(tokenA, tokenB, liquidity, amountAMin, amountBMin, to, deadline);
    }
    function removeLiquidityETHWithPermit(
            address token,
            uint liquidity,
            uint amountTokenMin,
            uint amountETHMin,
            address to,
            uint deadline,
            bool approveMax, uint8 v, bytes32 r, bytes32 s
    ) external override returns (uint amountToken, uint amountETH) {
        address pair = UniswapV2Library.pairFor(factory, token, WETH);
        uint value = approveMax ? uint(-1) : liquidity;
        IUniswapV2Pair(pair).permit(msg.sender, address(this), value, deadline, v, r, s);
        (amountToken, amountETH) = removeLiquidityETH(token, liquidity, amountTokenMin, amountETHMin, to, deadline);
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
    function swapTokensForExactTokens(
            uint amountOut,
            uint amountInMax,
            address[] calldata path,
            address to,
            uint deadline
    ) external override ensure(deadline) returns (uint[] memory amounts) {
        amounts = UniswapV2Library.getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= amountInMax, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        TransferHelper.safeTransferFrom(path[0], msg.sender, UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, to);
    }
    function swapExactETHForTokens(uint amountOutMin, address[] calldata path, address to, uint deadline)
    external
            override
    payable
    ensure(deadline)
    returns (uint[] memory amounts)
    {
        require(path[0] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = UniswapV2Library.getAmountsOut(factory, msg.value, path);
        require(amounts[amounts.length - 1] >= amountOutMin, 'UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT');
        IWETH(WETH).deposit{value: amounts[0]}();
        assert(IWETH(WETH).transfer(UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]));
        _swap(amounts, path, to);
    }
    function swapTokensForExactETH(uint amountOut, uint amountInMax, address[] calldata path, address to, uint deadline)
    external
            override
    ensure(deadline)
    returns (uint[] memory amounts)
    {
        require(path[path.length - 1] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = UniswapV2Library.getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= amountInMax, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        TransferHelper.safeTransferFrom(path[0], msg.sender, UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, address(this));
        IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        TransferHelper.safeTransferETH(to, amounts[amounts.length - 1]);
    }
    public String swapExactTokensForETH(uint amountIn, uint amountOutMin, address[] calldata path, address to, uint deadline)
    external
            override
    ensure(deadline)
    returns (uint[] memory amounts)
    {
        require(path[path.length - 1] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = UniswapV2Library.getAmountsOut(factory, amountIn, path);
        require(amounts[amounts.length - 1] >= amountOutMin, 'UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT');
        TransferHelper.safeTransferFrom(path[0], msg.sender, UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]);
        _swap(amounts, path, address(this));
        IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        TransferHelper.safeTransferETH(to, amounts[amounts.length - 1]);
    }

    @Payable
    public String swapETHForExactTokens(uint amountOut, address[] calldata path, address to, uint deadline)
    ensure(deadline)
    returns (uint[] memory amounts)
    {
        require(path[0] == WETH, 'UniswapV2Router: INVALID_PATH');
        amounts = UniswapV2Library.getAmountsIn(factory, amountOut, path);
        require(amounts[0] <= msg.value, 'UniswapV2Router: EXCESSIVE_INPUT_AMOUNT');
        IWETH(WETH).deposit{value: amounts[0]}();
        assert(IWETH(WETH).transfer(UniswapV2Library.pairFor(factory, path[0], path[1]), amounts[0]));
        _swap(amounts, path, to);
        if (msg.value > amounts[0]) TransferHelper.safeTransferETH(msg.sender, msg.value - amounts[0]); // refund dust eth, if any
    }

    function quote(uint amountA, uint reserveA, uint reserveB) public pure override returns (uint amountB) {
        return UniswapV2Library.quote(amountA, reserveA, reserveB);
    }

    function getAmountOut(uint amountIn, uint reserveIn, uint reserveOut) public pure override returns (uint amountOut) {
        return UniswapV2Library.getAmountOut(amountIn, reserveIn, reserveOut);
    }

    function getAmountIn(uint amountOut, uint reserveIn, uint reserveOut) public pure override returns (uint amountIn) {
        return UniswapV2Library.getAmountOut(amountOut, reserveIn, reserveOut);
    }

    function getAmountsOut(uint amountIn, address[] memory path) public view override returns (uint[] memory amounts) {
        return UniswapV2Library.getAmountsOut(factory, amountIn, path);
    }

    function getAmountsIn(uint amountOut, address[] memory path) public view override returns (uint[] memory amounts) {
        return UniswapV2Library.getAmountsIn(factory, amountOut, path);
    }

    private static BigInteger MINIMUM_LIQUIDITY = BigInteger.valueOf(1_000);    // Minimum Liquidity
    private static Address BURNER_ADDR = new Address("");

    private Address factory;
    private Address lp;
    private Address token0;
    private Address token1;

    private BigInteger reserve0;
    private BigInteger reserve1;
    private BigInteger blockTimestampLast;

    private BigInteger price0CumulativeLast;
    private BigInteger price1CumulativeLast;
    private BigInteger kLast;

    private BigInteger THREE = BigInteger.valueOf(3);
    private BigInteger TWO   = BigInteger.valueOf(2);
    private BigInteger ONE_THOUSAND   = BigInteger.valueOf(1000);

    private int unlocked = 1;

    protected void lock(){
        require(unlocked == 1, "UniswapV2: LOCKED");
        unlocked = 0;
    }

    protected void unlock(){
        require(unlocked == 0, "UniswapV2: LOCKED");
        unlocked = 1;
    }

    /**
     * Constructor
     *
     * @param depositToken Staking token
     * @param treasury  Treasury Address that will receive Contract Revenue
     * */
    public NulswapPair() {

        factory = Msg.sender();

    }

    public void initialize(Address _token0, Address _token1){
        require(Msg.sender().equals(factory), "UniswapV2: FORBIDDEN"); // sufficient check
        token0 = _token0;
        token1 = _token1;

        String _asset =  Utils.deploy(new String[]{ "lp", "i"+ BigInteger.valueOf(Block.timestamp()).toString()}, new Address("NULSd6HgzFMHJST31LPXG59utwyzyYX6rtPKx"), new String[]{"wNuls", "WNULS", "1", "8"});
        this.lp = new Address(_asset);
    }

    public static BigInteger Q112 = BigInteger.valueOf(2).pow(112);

    // encode a uint112 as a UQ112x112
    private BigInteger encode(BigInteger y){
        return y.multiply(Q112); // never overflows
    }

    // divide a UQ112x112 by a uint112, returning a UQ112x112
    private BigInteger uqdiv(BigInteger x, BigInteger y){
        return x.divide(y);
    }

    // update reserves and, on the first call per block, price accumulators
    private void _update(BigInteger balance0, BigInteger balance1, BigInteger _reserve0, BigInteger _reserve1){
        require(balance0.compareTo(Q112.subtract(BigInteger.ONE)) <= 0 && balance1.compareTo(Q112.subtract(BigInteger.ONE)) <= 0, "UniswapV2: OVERFLOW");

        BigInteger blockTimestamp   = BigInteger.valueOf(Block.timestamp()).remainder(TWO.pow(32));
        BigInteger timeElapsed      = blockTimestamp.subtract(blockTimestampLast); // overflow is desired

        if (timeElapsed.compareTo(BigInteger.ZERO) > 0 && _reserve0.compareTo(BigInteger.ZERO) != 0 && _reserve1.compareTo(BigInteger.ZERO) != 0) {
            // * never overflows, and + overflow is desired
            price0CumulativeLast = price0CumulativeLast.add(uqdiv(encode(_reserve1), _reserve0).multiply(timeElapsed));
            price1CumulativeLast = price1CumulativeLast.add(uqdiv(encode(_reserve0), _reserve1).multiply(timeElapsed));
        }

        reserve0 = balance0;
        reserve1 = balance1;
        blockTimestampLast = blockTimestamp;
        //emit Sync(reserve0, reserve1);
    }

    // if fee is on, mint liquidity equivalent to 1/6th of the growth in sqrt(k)

    private boolean _mintFee(BigInteger _reserve0, BigInteger _reserve1) {

        Address feeTo = getFeeTo();//IUniswapV2Factory(factory).feeTo();
        boolean feeOn = !feeTo.equals(BURNER_ADDR);
        BigInteger _kLast = kLast; // gas savings
        if (feeOn) {
            if (_kLast.compareTo(BigInteger.ZERO) != 0) {

                BigInteger rootK = sqrt(_reserve0.multiply(_reserve1));
                BigInteger rootKLast = sqrt(_kLast);
                if (rootK.compareTo(rootKLast) > 0) {
                    BigInteger numerator = safeTotalSupply(lp).multiply(rootK.subtract(rootKLast));
                    BigInteger denominator = rootK.multiply(BigInteger.valueOf(5)).add(rootKLast);
                    BigInteger liquidity = numerator.divide(denominator);
                    if (liquidity.compareTo(BigInteger.ZERO) > 0)
                        _mint(feeTo, liquidity);
                }
            }
        } else if (_kLast.compareTo(BigInteger.ZERO) != 0) {
            kLast = BigInteger.ZERO;
        }
        return feeOn;
    }

    private BigInteger min(BigInteger x, BigInteger y){
        BigInteger z;
        z = x.compareTo(y) < 0 ? x : y;
        return z;
    }

    private BigInteger sqrt(BigInteger y){
        BigInteger z = BigInteger.ZERO;
        if (y.compareTo(THREE) > 0) {
            z = y;
            BigInteger x = y.divide(TWO).add(BigInteger.ONE);
            while (x.compareTo(z) < 0) {
                z = x;
                x = (y.divide(x).add(x)).divide(TWO);
            }
        } else if (y.compareTo(BigInteger.ZERO) != 0) {
            z = BigInteger.ONE;
        }
        return z;
    }

    public BigInteger mint(Address to) {

        lock();


        BigInteger _reserve0 = reserve0;
        BigInteger _reserve1 = reserve1; // gas savings

        BigInteger balance0 = safeBalanceOf(token0, Msg.address());//IERC20(token0).balanceOf(address(this));
        BigInteger balance1 = safeBalanceOf(token1, Msg.address()); //IERC20(token1).balanceOf(address(this));

        BigInteger amount0 = balance0.subtract(_reserve0);
        BigInteger amount1 = balance1.subtract(_reserve1);

        boolean feeOn = _mintFee(_reserve0, _reserve1);
        BigInteger liquidity;
        BigInteger _totalSupply = safeTotalSupply(lp); // gas savings, must be defined here since totalSupply can update in _mintFee
        if (_totalSupply.compareTo(BigInteger.ZERO) == 0) {
            liquidity = sqrt(amount0.multiply(amount1)).subtract(MINIMUM_LIQUIDITY);
            _mint(BURNER_ADDR, MINIMUM_LIQUIDITY); // permanently lock the first MINIMUM_LIQUIDITY tokens
        } else {
            liquidity = min(amount0.multiply(_totalSupply).divide(_reserve0), amount1.multiply(_totalSupply).divide(_reserve1));
        }
        require(liquidity.compareTo(BigInteger.ZERO) > 0, "UniswapV2: INSUFFICIENT_LIQUIDITY_MINTED");
        _mint(to, liquidity);

        _update(balance0, balance1, _reserve0, _reserve1);
        if (feeOn) kLast = reserve0.multiply(reserve1); // reserve0 and reserve1 are up-to-date
        //emit Mint(msg.sender, amount0, amount1);

        unlock();
        return liquidity;
    }

    public String burn(Address to) {

        lock();

        BigInteger _reserve0 = reserve0;
        BigInteger _reserve1 = reserve1; // gas savings

        Address _token0      = token0;                                // gas savings
        Address _token1      = token1;                                // gas savings

        BigInteger balance0  = safeBalanceOf(_token0, Msg.address());//IERC20(_token0).balanceOf(address(this));
        BigInteger balance1  = safeBalanceOf(_token1, Msg.address());//IERC20(_token1).balanceOf(address(this));
        BigInteger liquidity = safeBalanceOf(lp, Msg.address());

        boolean feeOn = _mintFee(_reserve0, _reserve1);
        BigInteger _totalSupply =safeTotalSupply(lp); // gas savings, must be defined here since totalSupply can update in _mintFee
        BigInteger amount0                 = liquidity.multiply(balance0).divide(_totalSupply); // using balances ensures pro-rata distribution
        BigInteger amount1                 = liquidity.multiply(balance1).divide(_totalSupply); // using balances ensures pro-rata distribution

        require(amount0.compareTo(BigInteger.ZERO) > 0 && amount1.compareTo(BigInteger.ZERO) > 0, "UniswapV2: INSUFFICIENT_LIQUIDITY_BURNED");
        _burn(Msg.address(), liquidity);

        safeTransfer(_token0, to, amount0);
        safeTransfer(_token1, to, amount1);

        balance0 = safeBalanceOf(_token0, Msg.address());//IERC20(_token0).balanceOf(address(this));
        balance1 = safeBalanceOf(_token1, Msg.address());//IERC20(_token1).balanceOf(address(this));

        _update(balance0, balance1, _reserve0, _reserve1);
        if (feeOn) kLast = reserve0.multiply(reserve1); // reserve0 and reserve1 are up-to-date
        //emit Burn(msg.sender, amount0, amount1, to);

        unlock();
        return amount0+","+amount1;
    }

    // this low-level function should be called from a contract which performs important safety checks
    public void swap(BigInteger amount0Out, BigInteger amount1Out, Address to/*, bytes calldata data*/) {

        lock();

        require(amount0Out.compareTo(BigInteger.ZERO) > 0 || amount1Out.compareTo(BigInteger.ZERO) > 0, "UniswapV2: INSUFFICIENT_OUTPUT_AMOUNT");

        BigInteger _reserve0 = reserve0;
        BigInteger _reserve1 = reserve1; // gas savings

        require(amount0Out.compareTo(_reserve0) < 0 && amount1Out.compareTo(_reserve1) < 0, "UniswapV2: INSUFFICIENT_LIQUIDITY");

        BigInteger balance0;
        BigInteger balance1;

        Address _token0 = token0;
        Address _token1 = token1;

        require(to != _token0 && to != _token1, "UniswapV2: INVALID_TO");

        if (amount0Out.compareTo(BigInteger.ZERO) > 0) safeTransfer(_token0, to, amount0Out); // optimistically transfer tokens
        if (amount1Out.compareTo(BigInteger.ZERO) > 0) safeTransfer(_token1, to, amount1Out); // optimistically transfer tokens
        //if (data.length > 0) IUniswapV2Callee(to).uniswapV2Call(msg.sender, amount0Out, amount1Out, data);
        balance0 = safeBalanceOf(_token0, Msg.address()); //IERC20(_token0).balanceOf(address(this));
        balance1 = safeBalanceOf(_token1, Msg.address()); //IERC20(_token1).balanceOf(address(this));

        BigInteger amount0In = balance0.compareTo(_reserve0.subtract(amount0Out)) > 0 ? balance0.subtract((_reserve0.subtract(amount0Out))) : BigInteger.ZERO;
        BigInteger amount1In = balance1.compareTo(_reserve1.subtract(amount1Out)) > 0 ? balance1.subtract((_reserve1.subtract(amount1Out))) : BigInteger.ZERO;
        require(amount0In.compareTo(BigInteger.ZERO) > 0 || amount1In.compareTo(BigInteger.ZERO) > 0, "UniswapV2: INSUFFICIENT_INPUT_AMOUNT");

        BigInteger balance0Adjusted = balance0.multiply(ONE_THOUSAND).subtract(amount0In.multiply(THREE));
        BigInteger balance1Adjusted = balance1.multiply(ONE_THOUSAND).subtract(amount1In.multiply(THREE));

        require((balance0Adjusted.multiply(balance1Adjusted)).compareTo(_reserve0.multiply(_reserve1).multiply(ONE_THOUSAND.pow(2))) >= 0, "UniswapV2: K");

        _update(balance0, balance1, _reserve0, _reserve1);
        //emit Swap(msg.sender, amount0In, amount1In, amount0Out, amount1Out, to);

        unlock();
    }

    // force balances to match reserves
    public void skim(Address to){

        lock();

        Address _token0 = token0; // gas savings
        Address _token1 = token1; // gas savings
        safeTransfer(_token0, to, safeBalanceOf(_token0, Msg.address()).subtract(reserve0) /* IERC20(_token0).balanceOf(address(this)).sub(reserve0)*/);
        safeTransfer(_token1, to, safeBalanceOf(_token1, Msg.address()).subtract(reserve1)/*IERC20(_token1).balanceOf(address(this)).sub(reserve1)*/);

        unlock();
    }

    // force reserves to match balances
    public void sync(){

        lock();

        _update(safeBalanceOf(token0, Msg.address()),  safeBalanceOf(token1, Msg.address()), reserve0, reserve1);

        unlock();
    }

    private Address getFeeTo(){
        String[][] argsM = new String[][]{};
        return new Address(factory.callWithReturnValue("feeTo", "", argsM, BigInteger.ZERO));
    }

    private void _mint(@Required Address recipient, @Required BigInteger amount){
        String[][] argsM =  new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        lp.callWithReturnValue("_mint", "", argsM, BigInteger.ZERO);
    }

    private void _burn(@Required Address recipient, @Required BigInteger amount){
        String[][] argsM =  new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
       lp.callWithReturnValue("_burn", "", argsM, BigInteger.ZERO);
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

}