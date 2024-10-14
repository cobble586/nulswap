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
public class NulswapPair implements Contract{

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