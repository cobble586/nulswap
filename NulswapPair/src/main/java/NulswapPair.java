import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import org.checkerframework.checker.units.qual.A;
import io.nuls.contract.sdk.Utils.*;
import io.nuls.contract.sdk.event.DebugEvent;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

/**
 * @title   Nulswap Pair
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

    private static BigInteger MINIMUM_LIQUIDITY = BigInteger.valueOf(1_000);                            // Minimum Liquidity
    private static Address BURNER_ADDR          = new Address("NULSd6HgsVSzCAJwLYBjvfP3NwbKCvV525GWn"); // Burner Address
    private static BigInteger Q112              = BigInteger.valueOf(2).pow(112);                       // 2^112

    private BigInteger THREE          = BigInteger.valueOf(3);                          // Three
    private BigInteger TWO            = BigInteger.valueOf(2);                          // Two
    private BigInteger ONE_THOUSAND   = BigInteger.valueOf(1000);                       // One Thousand

    private Address factory;                    // Factory Address
    private Address lp;                         // Lp Token
    private Address token0;                     // Pair Token0
    private Address token1;                     // Pair Token1

    private BigInteger reserve0;                // Resserve Token0
    private BigInteger reserve1;                // Reserve Token1
    private BigInteger blockTimestampLast;      // Last time pair updated

    private BigInteger price0CumulativeLast;    // Accumulative Token0 Price
    private BigInteger price1CumulativeLast;    // Accumulative Token1 Price
    private BigInteger kLast;                   // kLast

    private int unlocked = 1;                   // Lock Status | 1 - unlocked 0 - locked

    /**
     * Lock Contract
     * @dev Essential to protect against reentrancy attacks
     * */
    protected void lock(){
        require(unlocked == 1, "NulswapV3: LOCKED");
        unlocked = 0;
    }

    /**
     * Unlock Contract
     *
     * */
    protected void unlock(){
        require(unlocked == 0, "NulswapV3: LOCKED");
        unlocked = 1;
    }

    /**
     * Constructor
     *
     * */
    public NulswapPair() {

        factory              = Msg.sender();
        reserve0             = BigInteger.ZERO;
        reserve1             = BigInteger.ZERO;
        price0CumulativeLast =  BigInteger.ZERO;
        price1CumulativeLast =  BigInteger.ZERO;
        kLast                = BigInteger.ZERO;
        blockTimestampLast   = BigInteger.ZERO;
    }

    /**
     * Initialize pair
     *
     * @param _token0 Token0 Address
     * @param _token1 Token1 Address
     *
     * @dev Creates a new lp token that will be associated with the pair
     * */
    public void initialize(Address _token0, Address _token1){

        require(Msg.sender().equals(factory), "NulswapV3: FORBIDDEN"); // sufficient check

        token0 = _token0;
        token1 = _token1;

        String _asset = Utils.deploy(new String[]{ "lp", "i"+ BigInteger.valueOf(Block.timestamp()).toString()}, new Address("NULSd6HgqveRoAvXCF996wb2nPE7SZs9Yehjz"), new String[]{"Nulswap_lp", "NSWAP_LP", "8"});
        this.lp = new Address(_asset);
    }

    /**
     *  Encode a uint112 as a UQ112x112
     *
     * @param y Amount to encode
     * */
    private BigInteger encode(BigInteger y){
        return y.multiply(Q112);
    }

    /**
     * Divide a UQ112x112 by a uint112, returning a UQ112x112
     *
     * @param x Amount to divide
     * @param y Amount to be divided by
     * */
    private BigInteger uqdiv(BigInteger x, BigInteger y){
        return x.divide(y);
    }

    /**
     * Update reserves and, on the first call per block, price accumulators
     *
     * @param balance0 New reserve0
     * @param balance1 New reserve1
     * @param _reserve0 Old reserve0
     * @param _reserve1 Old reserve1
     * */
    private void _update(BigInteger balance0, BigInteger balance1, BigInteger _reserve0, BigInteger _reserve1){

        require(balance0.compareTo(Q112.subtract(BigInteger.ONE)) <= 0 && balance1.compareTo(Q112.subtract(BigInteger.ONE)) <= 0, "NulswapV3: OVERFLOW");

        BigInteger blockTimestamp   = BigInteger.valueOf(Block.timestamp()).remainder(TWO.pow(32));
        BigInteger timeElapsed      = blockTimestamp.subtract(blockTimestampLast);

        if (timeElapsed.compareTo(BigInteger.ZERO) > 0 && _reserve0.compareTo(BigInteger.ZERO) != 0 && _reserve1.compareTo(BigInteger.ZERO) != 0) {

            price0CumulativeLast = price0CumulativeLast.add(uqdiv(encode(_reserve1), _reserve0).multiply(timeElapsed));
            price1CumulativeLast = price1CumulativeLast.add(uqdiv(encode(_reserve0), _reserve1).multiply(timeElapsed));

        }

        reserve0            = balance0;
        reserve1            = balance1;
        blockTimestampLast  = blockTimestamp;
        //emit Sync(reserve0, reserve1);
    }

    // if fee is on, mint liquidity equivalent to 1/6th of the growth in sqrt(k)
    private boolean _mintFee(BigInteger _reserve0, BigInteger _reserve1) {

        Address feeTo = getFeeTo();//IUniswapV2Factory(factory).feeTo();
        boolean feeOn = !feeTo.equals(BURNER_ADDR);
        BigInteger _kLast = kLast;

        // if fee is on, mint liquidity equivalent to 1/6th of the growth in sqrt(k)
        if (feeOn) {

            if (_kLast.compareTo(BigInteger.ZERO) != 0) {

                BigInteger rootK     = sqrt(_reserve0.multiply(_reserve1));
                BigInteger rootKLast = sqrt(_kLast);

                if (rootK.compareTo(rootKLast) > 0) {

                    BigInteger numerator    = safeTotalSupply(lp).multiply(rootK.subtract(rootKLast));
                    BigInteger denominator  = rootK.multiply(BigInteger.valueOf(5)).add(rootKLast);
                    BigInteger liquidity    = numerator.divide(denominator);

                    if (liquidity.compareTo(BigInteger.ZERO) > 0)
                        _mint(feeTo, liquidity);

                }
            }

        } else if (_kLast.compareTo(BigInteger.ZERO) != 0) {
            kLast = BigInteger.ZERO;
        }
        return feeOn;
    }

    /**
     * Return the lowest amount
     *
     * @param x The first amount
     * @param y The second amount
     * */
    private BigInteger min(BigInteger x, BigInteger y){
        return x.compareTo(y) < 0 ? x : y;
    }

    /**
     * Squares value
     *
     * @param y Amount to be squared
     * */
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

    /**
     * Mint Liquidity
     *
     * @param to Address that receive the ownerhsip of the liquidity
     * */
    public BigInteger mint(Address to) {

        lock();

        Utils.emit(new DebugEvent("test2", "1.1.1"));
        BigInteger balance0     = safeBalanceOf(token0, Msg.address()); //IERC20(token0).balanceOf(address(this));
        BigInteger balance1     = safeBalanceOf(token1, Msg.address()); //IERC20(token1).balanceOf(address(this));

        BigInteger amount0      = balance0.subtract(reserve0);
        BigInteger amount1      = balance1.subtract(reserve1);

        boolean feeOn           = _mintFee(reserve0, reserve1);
        BigInteger _totalSupply = safeTotalSupply(lp); // Must be defined here since totalSupply can update in _mintFee

        Utils.emit(new DebugEvent("test2", "1.1.2"));
        BigInteger liquidity;
        if (_totalSupply.compareTo(BigInteger.ONE) == 0) {
            liquidity = sqrt(amount0.multiply(amount1)).subtract(MINIMUM_LIQUIDITY);
            _mint(BURNER_ADDR, MINIMUM_LIQUIDITY); // permanently lock the first MINIMUM_LIQUIDITY tokens
        } else {
            liquidity = min(amount0.multiply(_totalSupply).divide(reserve0), amount1.multiply(_totalSupply).divide(reserve1));
        }

        Utils.emit(new DebugEvent("test2", "1.1.3"));

        require(liquidity.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_LIQUIDITY_MINTED");
        _mint(to, liquidity);
        Utils.emit(new DebugEvent("test2", "1.1.4"));
        _update(balance0, balance1, reserve0, reserve1);
        Utils.emit(new DebugEvent("test2", "1.1.5"));
        if (feeOn) kLast = reserve0.multiply(reserve1); // reserve0 and reserve1 are up-to-date
        //emit Mint(msg.sender, amount0, amount1);

        unlock();

        return liquidity;
    }

    public String burn(Address to) {

        lock();

        BigInteger balance0     = safeBalanceOf(token0, Msg.address()); //IERC20(_token0).balanceOf(address(this));
        BigInteger balance1     = safeBalanceOf(token1, Msg.address()); //IERC20(_token1).balanceOf(address(this));
        BigInteger liquidity    = safeBalanceOf(lp, Msg.address());

        boolean feeOn           = _mintFee(reserve0, reserve1);
        BigInteger _totalSupply = safeTotalSupply(lp); // Must be defined here since totalSupply can update in _mintFee
        BigInteger amount0      = liquidity.multiply(balance0).divide(_totalSupply); // using balances ensures pro-rata distribution
        BigInteger amount1      = liquidity.multiply(balance1).divide(_totalSupply); // using balances ensures pro-rata distribution

        require(amount0.compareTo(BigInteger.ZERO) > 0 && amount1.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_LIQUIDITY_BURNED");
        _burn(Msg.address(), liquidity);

        safeTransfer(token0, to, amount0);
        safeTransfer(token1, to, amount1);

        balance0 = safeBalanceOf(token0, Msg.address()); //IERC20(_token0).balanceOf(address(this));
        balance1 = safeBalanceOf(token1, Msg.address()); //IERC20(_token1).balanceOf(address(this));

        _update(balance0, balance1, reserve0, reserve1);
        if (feeOn) kLast = reserve0.multiply(reserve1); // reserve0 and reserve1 are up-to-date
        //emit Burn(msg.sender, amount0, amount1, to);

        unlock();

        return amount0 + "," + amount1;
    }

    // this low-level function should be called from a contract which performs important safety checks
    public void swap(BigInteger amount0Out, BigInteger amount1Out, Address to) {

        // Lock Contract
        lock();

        // One of the values must be higher than 0
        require(amount0Out.compareTo(BigInteger.ZERO) > 0 || amount1Out.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_OUTPUT_AMOUNT");
        require(amount0Out.compareTo(reserve0) < 0 && amount1Out.compareTo(reserve1) < 0, "NulswapV3: INSUFFICIENT_LIQUIDITY");

        BigInteger balance0, balance1;

        require(to != token0 && to != token1, "NulswapV3: INVALID_TO");

        if (amount0Out.compareTo(BigInteger.ZERO) > 0) safeTransfer(token0, to, amount0Out); // optimistically transfer tokens
        if (amount1Out.compareTo(BigInteger.ZERO) > 0) safeTransfer(token1, to, amount1Out); // optimistically transfer tokens

        balance0 = safeBalanceOf(token0, Msg.address()); //IERC20(_token0).balanceOf(address(this));
        balance1 = safeBalanceOf(token1, Msg.address()); //IERC20(_token1).balanceOf(address(this));

        BigInteger amount0In = balance0.compareTo(reserve0.subtract(amount0Out)) > 0 ? balance0.subtract((reserve0.subtract(amount0Out))) : BigInteger.ZERO;
        BigInteger amount1In = balance1.compareTo(reserve1.subtract(amount1Out)) > 0 ? balance1.subtract((reserve1.subtract(amount1Out))) : BigInteger.ZERO;

        require(amount0In.compareTo(BigInteger.ZERO) > 0 || amount1In.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_INPUT_AMOUNT");

        BigInteger balance0Adjusted = balance0.multiply(ONE_THOUSAND).subtract(amount0In.multiply(THREE));
        BigInteger balance1Adjusted = balance1.multiply(ONE_THOUSAND).subtract(amount1In.multiply(THREE));

        require((balance0Adjusted.multiply(balance1Adjusted)).compareTo(reserve0.multiply(reserve1).multiply(ONE_THOUSAND.pow(2))) >= 0, "NulswapV3: K");

        _update(balance0, balance1, reserve0, reserve1);
        //emit Swap(msg.sender, amount0In, amount1In, amount0Out, amount1Out, to);

        unlock();
    }

    /**
     *  Force balances to match reserves
     *
     * @param to Address that will receive the excess
     *
     * @dev If balance is 40 and reserve is 28 then the excess
     *      of 12 is sent to the Address ´to´
     * */
    public void skim(Address to){

        lock();

        safeTransfer(token0, to, safeBalanceOf(token0, Msg.address()).subtract(reserve0) /* IERC20(_token0).balanceOf(address(this)).sub(reserve0)*/);
        safeTransfer(token1, to, safeBalanceOf(token1, Msg.address()).subtract(reserve1) /*IERC20(_token1).balanceOf(address(this)).sub(reserve1)*/);

        unlock();
    }

    /**
     * Force reserves to match balances
     *
     * */
    public void sync(){

        lock();

        _update(safeBalanceOf(token0, Msg.address()),  safeBalanceOf(token1, Msg.address()), reserve0, reserve1);

        unlock();
    }

    /**
     * Get Address where fee goes
     *
     * */
    private Address getFeeTo(){
        String[][] argsM = new String[][]{};
        return new Address(factory.callWithReturnValue("getFeeTo", "", argsM, BigInteger.ZERO));
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
        require(b, "NulswapV3: Failed to transfer");
    }

    @View
    public Address getFactory(){
        return factory;
    }

    @View
    public Address getLP(){
        return lp;
    }

    @View
    public Address getToken0(){
        return token0;
    }

    @View
    public Address getToken1(){
        return token1;
    }

    @View
    public BigInteger getReserve0(){
        return reserve0;
    }

    @View
    public BigInteger getReserve1(){
        return reserve1;
    }

    @View
    public String getReserves(){
        return reserve0 + "," + reserve1;
    }

    @View
    public BigInteger getBlockTimeStampLast(){
        return blockTimestampLast;
    }

    @View
    public BigInteger getPrice0CumulativeLast(){
        return price0CumulativeLast;
    }

    @View
    public BigInteger getPrice1CumulativeLast(){
        return price1CumulativeLast;
    }

    @View
    public BigInteger getKLast(){
        return kLast;
    }

    @View
    public Integer getUnlocked(){
        return unlocked;
    }

    @View
    public Address getBurnerAddress(){
        return BURNER_ADDR;
    }

    @View
    public BigInteger getQ112(){
        return Q112;
    }
}