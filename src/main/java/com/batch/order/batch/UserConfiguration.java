package com.batch.order.batch;

import com.batch.order.entity.User;
import com.batch.order.order.JobParametersDecide;
import com.batch.order.order.OrderStatistics;
import com.batch.order.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.BeanWrapperFieldExtractor;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class UserConfiguration {
    private final int CHUNK = 100;

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final UserRepository userRepository;
    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;

    /**
     * --job.name=userJob
     * -date=2020-11 --job.name=userJob
     * @return
     */
    @Bean
    public Job userJob() throws Exception {
        return this.jobBuilderFactory.get("userJob")
                .incrementer(new RunIdIncrementer()) // Batch parameter increment
                /* ????????? ?????? ?????? */
                .start(this.userStep())
                .next(this.userLevelUpStep())
                /* ????????? ????????? ?????? */
                .listener(new LevelUpJobExecutionListener(userRepository))
                //.next(this.orderStatisticsStep(null))
                /** orderStatisticsStep ?????? */
                .next(new JobParametersDecide("date"))
                    .on(JobParametersDecide.CONTINUE.getName()) /* CONTINUE ?????? */
                    .to(this.orderStatisticsStep(null))
                    .build()
                .build();
    }

    /**
     * ?????? ?????? Step
     * @param date
     * @return
     */
    @Bean
    @JobScope
    public Step orderStatisticsStep(@Value("#{jobParameters[date]}") String date) throws Exception {
        return this.stepBuilderFactory.get("orderStatisticsStep")
                .<OrderStatistics, OrderStatistics>chunk(CHUNK)
                .reader(this.orderStatisticsItemReader(date))
                .writer(this.orderStatisticsItemWriter(date))
                .build();
    }

    /**
     * itemReader ?????? ????????? OrderStatistics ???????????? ?????? ????????????
     * @param date
     * @return
     */
    private ItemWriter<? super OrderStatistics> orderStatisticsItemWriter(String date) throws Exception {
        /**
         * step ??? ????????? ????????? ????????? ????????? ???????????? ?????????
         * chunk ?????? ???????????? ????????? ????????? ???????????? ??????
         */
        YearMonth yearMonth = YearMonth.parse(date);

        /* ????????? ?????? */
        String fileName = yearMonth.getYear() + "???_" + yearMonth.getMonthValue() + "???_??????_??????_??????.csv";

        BeanWrapperFieldExtractor<OrderStatistics> fieldExtractor = new BeanWrapperFieldExtractor<>();
        fieldExtractor.setNames(new String[] {"amount", "date"});

        DelimitedLineAggregator<OrderStatistics> lineAggregator = new DelimitedLineAggregator<>();
        lineAggregator.setDelimiter(","); /* ????????? */
        lineAggregator.setFieldExtractor(fieldExtractor);

        FlatFileItemWriter<OrderStatistics> itemWriter = new FlatFileItemWriterBuilder<OrderStatistics>()
                .resource(new FileSystemResource("excelfile/" + fileName))
                .lineAggregator(lineAggregator)
                .name("orderStatisticsItemWriter")
                .encoding("UTF-8")
                .headerCallback(writer -> writer.write("total_amount, date"))
                .build();

        itemWriter.afterPropertiesSet();

        return itemWriter;
    }

    /**
     * ????????? ?????? ????????? ????????? ??????
     * @param date
     * @return
     */
    private ItemReader<? extends OrderStatistics> orderStatisticsItemReader(String date) throws Exception {
        YearMonth yearMonth = YearMonth.parse(date);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("startDate", yearMonth.atDay(1));
        parameters.put("endDate", yearMonth.atEndOfMonth()); // ?????? ?????? ????????? ?????? ?????????

        Map<String, Order> sortKey = new HashMap<>();
        sortKey.put("created_date", Order.ASCENDING); // ?????? ????????????

        JdbcPagingItemReader<OrderStatistics> itemReader = new JdbcPagingItemReaderBuilder<OrderStatistics>()
                .dataSource(this.dataSource)
                .rowMapper((resultSet, i) -> OrderStatistics.builder()
                        .amount(resultSet.getString(1))
                        .date(LocalDate.parse(resultSet.getString(2), DateTimeFormatter.ISO_DATE))
                        .build()) // ????????? orders ????????? ???????????? OrderStatistics ??? ??????
                .pageSize(CHUNK)
                .name("orderStatisticsItemReader")
                /* select ?????? */
                .selectClause("sum(amount), created_date") /* ?????? ?????? */
                .fromClause("orders") /* orders ????????? ?????? */
                .whereClause("created_date >= :startDate and created_date <= :endDate")
                .groupClause("created_date")
                .parameterValues(parameters) // startDate, endDate ???????????? ?????? ??????
                .sortKeys(sortKey)
                .build();

        itemReader.afterPropertiesSet();

        return itemReader;
    }

    /**
     * Tasklet Step
     * @return
     */
    @Bean
    public Step userStep() {
        return this.stepBuilderFactory.get("userStep")
                /* user Data 400 ??? ?????? */
                /* Step ??? ???????????? 1. Tasklet ?????? */
                .tasklet(new SaveUserTasklet(userRepository))
                .build();
    }

    /**
     * Chunk Step
     * @return
     * @throws Exception
     */
    @Bean
    public Step userLevelUpStep() throws Exception {
        /* Step ??? ?????? ?????? 2. Chunk (reader-processor-writer) */
        return this.stepBuilderFactory.get("userLevelUpStep")
                .<User, User>chunk(CHUNK)
                .reader(this.itemReader())
                .processor(this.itemProcessor())
                .writer(this.itemWriter())
                .build();
    }

    /**
     * ItemWriter
     * @return
     */
    private ItemWriter<? super User> itemWriter() {
        return users -> {
            users.forEach(x -> {
                x.levelUp(); // ????????? ??????
                userRepository.save(x);
                log.info("id: " + x.getId());
            });
        };
    }

    /**
     * ItemProcessor
     * @return
     */
    private ItemProcessor<? super User,? extends User> itemProcessor() {
        /* ?????? ?????? ?????? ?????? ?????? */
        return user -> {
            if (user.availableLevelUp()) { /* ?????? ?????? ?????? ?????? ?????? */
                log.info("user update target id: " + user.getId());
                return user;
            }

            return null;
        };
    }

    /**
     * ItemReader
     * @return
     * @throws Exception
     */
    private ItemReader<? extends User> itemReader() throws Exception {
        /* JpaPagingItemReaderBuilder */
        JpaPagingItemReader<User> itemReader = new JpaPagingItemReaderBuilder<User>()
                .queryString("select u from User u")
                .entityManagerFactory(entityManagerFactory) // JPA ?????????
                .pageSize(CHUNK) /* ?????? ???????????? ???????????? ?????? ?????????. */
                .name("userItemReader")
                .build();

        itemReader.afterPropertiesSet();
        return itemReader;

    }
}
